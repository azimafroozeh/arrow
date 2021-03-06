#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

set -e

: ${R_BIN:=RDsan}

# Build arrow
pushd /arrow/r

# Install R package dependencies
# NOTE: any changes here should also be done in docker_build_r.sh
${R_BIN} -e "remotes::install_deps(dependencies = TRUE)"

make clean
${R_BIN} CMD INSTALL --no-byte-compile .

pushd tests

export UBSAN_OPTIONS="print_stacktrace=1,suppressions=/arrow/r/tools/ubsan.supp"
${R_BIN} < testthat.R

popd

popd
