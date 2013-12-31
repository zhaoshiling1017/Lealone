/*
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codefollower.lealone.atomicdb.start;

import com.codefollower.lealone.atomicdb.config.LealoneConfigurationLoader;
import com.codefollower.lealone.atomicdb.service.CassandraDaemon;

public class LealoneStarter {
    public static void main(String[] args) {
        System.setProperty("cassandra.rpc_port", "9160");
        System.setProperty("cassandra.start_native_transport", "true"); //启用native server，用于支持CQL
        System.setProperty("cassandra.native_transport_port", "9042");

        System.setProperty("cassandra.config", "lealone.yaml");
        System.setProperty("cassandra-foreground", "true"); //打印输出到控制台

        //覆盖默认的ConfigurationLoader
        System.setProperty("cassandra.config.loader", LealoneConfigurationLoader.class.getCanonicalName());

        CassandraDaemon.main(new String[] {});
    }
}
