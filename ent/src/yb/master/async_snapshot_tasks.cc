// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.

#include "yb/master/async_snapshot_tasks.h"

#include "yb/common/transaction_error.h"
#include "yb/common/wire_protocol.h"

#include "yb/master/master.h"
#include "yb/master/ts_descriptor.h"
#include "yb/master/catalog_manager.h"

#include "yb/rpc/messenger.h"

#include "yb/tserver/backup.proxy.h"

#include "yb/util/flag_tags.h"
#include "yb/util/format.h"
#include "yb/util/logging.h"

namespace yb {
namespace master {

using std::string;
using tserver::TabletServerErrorPB;

////////////////////////////////////////////////////////////
// AsyncTabletSnapshotOp
////////////////////////////////////////////////////////////

namespace {

std::string SnapshotIdToString(const std::string& snapshot_id) {
  auto uuid = TryFullyDecodeTxnSnapshotId(snapshot_id);
  return uuid.IsNil() ? snapshot_id : uuid.ToString();
}

}

AsyncTabletSnapshotOp::AsyncTabletSnapshotOp(Master *master,
                                             ThreadPool* callback_pool,
                                             const scoped_refptr<TabletInfo>& tablet,
                                             const string& snapshot_id,
                                             tserver::TabletSnapshotOpRequestPB::Operation op)
  : enterprise::RetryingTSRpcTask(master,
                                  callback_pool,
                                  new PickLeaderReplica(tablet),
                                  tablet->table().get()),
    tablet_(tablet),
    snapshot_id_(snapshot_id),
    operation_(op) {
}

string AsyncTabletSnapshotOp::description() const {
  return Format("$0 Tablet Snapshot Operation $1 RPC $2",
                *tablet_, tserver::TabletSnapshotOpRequestPB::Operation_Name(operation_),
                SnapshotIdToString(snapshot_id_));
}

TabletId AsyncTabletSnapshotOp::tablet_id() const {
  return tablet_->tablet_id();
}

TabletServerId AsyncTabletSnapshotOp::permanent_uuid() const {
  return target_ts_desc_ != nullptr ? target_ts_desc_->permanent_uuid() : "";
}

bool AsyncTabletSnapshotOp::RetryAllowed(TabletServerErrorPB::Code code, const Status& status) {
  switch (code) {
    case TabletServerErrorPB::TABLET_NOT_FOUND:
      return false;
    case TabletServerErrorPB::INVALID_SNAPSHOT:
      return operation_ != tserver::TabletSnapshotOpRequestPB::RESTORE_ON_TABLET;
    default:
      return TransactionError(status) != TransactionErrorCode::kSnapshotTooOld;
  }
}

void AsyncTabletSnapshotOp::HandleResponse(int attempt) {
  server::UpdateClock(resp_, master_->clock());

  if (resp_.has_error()) {
    Status status = StatusFromPB(resp_.error().status());

    if (!RetryAllowed(resp_.error().code(), status)) {
      LOG_WITH_PREFIX(WARNING) << "Failed, NO retry: " << status;
      TransitionToCompleteState();
    } else {
      LOG_WITH_PREFIX(WARNING) << "Failed, will be retried: " << status;
    }
  } else {
    TransitionToCompleteState();
    VLOG_WITH_PREFIX(1) << "Complete";
  }

  if (state() != MonitoredTaskState::kComplete) {
    VLOG_WITH_PREFIX(1) << "TabletSnapshotOp task is not completed";
    return;
  }

  switch (operation_) {
    case tserver::TabletSnapshotOpRequestPB::CREATE_ON_TABLET: {
      // TODO: this class should not know CatalogManager API,
      //       remove circular dependency between classes.
      master_->catalog_manager()->HandleCreateTabletSnapshotResponse(
          tablet_.get(), resp_.has_error());
      return;
    }
    case tserver::TabletSnapshotOpRequestPB::RESTORE_ON_TABLET: {
      // TODO: this class should not know CatalogManager API,
      //       remove circular dependency between classes.
      master_->catalog_manager()->HandleRestoreTabletSnapshotResponse(
          tablet_.get(), resp_.has_error());
      return;
    }
    case tserver::TabletSnapshotOpRequestPB::DELETE_ON_TABLET: {
      // TODO: this class should not know CatalogManager API,
      //       remove circular dependency between classes.
      // HandleDeleteTabletSnapshotResponse handles only non transaction aware snapshots.
      // So prevent log flooding for transaction aware snapshots.
      if (!TryFullyDecodeTxnSnapshotId(snapshot_id_)) {
        master_->catalog_manager()->HandleDeleteTabletSnapshotResponse(
            snapshot_id_, tablet_.get(), resp_.has_error());
      }
      return;
    }
    case tserver::TabletSnapshotOpRequestPB::RESTORE_FINISHED:
      return;
    case tserver::TabletSnapshotOpRequestPB::CREATE_ON_MASTER: FALLTHROUGH_INTENDED;
    case tserver::TabletSnapshotOpRequestPB::DELETE_ON_MASTER: FALLTHROUGH_INTENDED;
    case tserver::TabletSnapshotOpRequestPB::RESTORE_SYS_CATALOG: FALLTHROUGH_INTENDED;
    case google::protobuf::kint32min: FALLTHROUGH_INTENDED;
    case google::protobuf::kint32max: FALLTHROUGH_INTENDED;
    case tserver::TabletSnapshotOpRequestPB::UNKNOWN: break; // Not handled.
  }

  FATAL_INVALID_ENUM_VALUE(tserver::TabletSnapshotOpRequestPB::Operation, operation_);
}

bool AsyncTabletSnapshotOp::SendRequest(int attempt) {
  tserver::TabletSnapshotOpRequestPB req;
  req.set_dest_uuid(permanent_uuid());
  req.add_tablet_id(tablet_->tablet_id());
  req.set_snapshot_id(snapshot_id_);
  req.set_operation(operation_);
  if (snapshot_schedule_id_) {
    req.set_schedule_id(snapshot_schedule_id_.data(), snapshot_schedule_id_.size());
  }
  if (restoration_id_) {
    req.set_restoration_id(restoration_id_.data(), restoration_id_.size());
  }
  if (snapshot_hybrid_time_) {
    req.set_snapshot_hybrid_time(snapshot_hybrid_time_.ToUint64());
  }
  if (has_metadata_) {
    req.set_schema_version(schema_version_);
    *req.mutable_schema() = schema_;
    *req.mutable_indexes() = indexes_;
    req.set_hide(hide_);
  }
  req.set_propagated_hybrid_time(master_->clock()->Now().ToUint64());

  ts_backup_proxy_->TabletSnapshotOpAsync(req, &resp_, &rpc_, BindRpcCallback());
  VLOG_WITH_PREFIX(1) << "Sent to " << permanent_uuid() << " (attempt " << attempt << "): "
                      << (VLOG_IS_ON(4) ? req.ShortDebugString() : "");
  return true;
}

void AsyncTabletSnapshotOp::Finished(const Status& status) {
  if (!callback_) {
    return;
  }
  if (!status.ok()) {
    callback_(status);
    return;
  }
  if (resp_.has_error()) {
    auto status = tablet_->CheckRunning();
    if (status.ok()) {
      status = StatusFromPB(resp_.error().status());
    }
    callback_(status);
  } else {
    callback_(&resp_);
  }
}

void AsyncTabletSnapshotOp::SetMetadata(const SysTablesEntryPB& pb) {
  has_metadata_ = true;
  schema_version_ = pb.version();
  schema_ = pb.schema();
  indexes_ = pb.indexes();
}

} // namespace master
} // namespace yb
