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

FROM arrowdev/arrow-python-3.6:latest

RUN export DEBIAN_FRONTEND=noninteractive && \
    apt-get update -y -q && \
    apt-get install -y -q --no-install-recommends \
        odbc-postgresql \
        postgresql \
        sudo \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# install turbodbc dependencies from conda-forge
RUN conda install -c conda-forge pybind11 pytest pytest-cov mock unixodbc && \
    conda clean --all

ENV TURBODBC_TEST_CONFIGURATION_FILES "query_fixtures_postgresql.json"

# build and test
CMD ["/bin/bash", "-c", "arrow/ci/docker_build_cpp.sh && \
    arrow/ci/docker_build_python.sh && \
    arrow/integration/turbodbc/runtest.sh"]
