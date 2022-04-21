#!/bin/bash



DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"
source ${DIR}/../../scripts/utils.sh

log "Do you want to delete the fully managed connectors ?"
check_if_continue

log "Deleting fully managed connector"
delete_ccloud_connector connector.json
delete_ccloud_connector connector2.json

maybe_delete_ccloud_environment