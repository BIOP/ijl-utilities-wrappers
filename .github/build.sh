#!/bin/bash
set -ex
curl -fsLO https://raw.githubusercontent.com/scijava/scijava-scripts/master/ci-build.sh
ls -l ci-build.sh
cat ci-build.sh | head -20
bash ci-build.sh