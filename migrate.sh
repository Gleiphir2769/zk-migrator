#!/bin/bash

MIGRATED=false

java -jar zk-migrator.jar -src $ZK_SRC -dst $ZK_DST

if [[ "$MIGRATED" == "true" ]]; then
    echo "Zookeeper migrate from $ZK_SRC to $ZK_DST has finished!"
else
    echo "Zookeeper migrate failed..."
fi