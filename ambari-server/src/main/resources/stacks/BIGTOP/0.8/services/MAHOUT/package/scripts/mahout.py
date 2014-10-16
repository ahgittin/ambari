"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Ambari Agent

"""
import os

from resource_management import *

def mahout():
  import params

  Directory( params.mahout_conf_dir,
    owner = params.hdfs_user,
    group = params.user_group
  )

  mahout_TemplateConfig( ['mahout-env.sh'])

  # mahout_properties is always set to a default even if it's not in the payload
  File(format("{mahout_conf_dir}/mahout.properties"),
              mode=0644,
              group=params.user_group,
              owner=params.hdfs_user,
              content=params.mahout_properties
  )

  if params.log4j_props:
    File(format("{mahout_conf_dir}/log4j.properties"),
      mode=0644,
      group=params.user_group,
      owner=params.hdfs_user,
      content=params.log4j_props
    )
  elif (os.path.exists(format("{mahout_conf_dir}/log4j.properties"))):
    File(format("{mahout_conf_dir}/log4j.properties"),
      mode=0644,
      group=params.user_group,
      owner=params.hdfs_user
    )

def mahout_TemplateConfig(name):
  import params

  if not isinstance(name, list):
    name = [name]

  for x in name:
    TemplateConfig( format("{mahout_conf_dir}/{x}"),
        owner = params.hdfs_user
    )
