/**
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

package org.apache.hive.service.auth;

import java.io.IOException;
import java.util.Map;

import javax.security.sasl.SaslException;

import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.hive.thrift.HadoopThriftAuthBridge;
import org.apache.hadoop.hive.thrift.HadoopThriftAuthBridge.Server;
import org.apache.hive.service.cli.thrift.ThriftCLIService;
import org.apache.hive.service.rpc.thrift.TCLIService;
import org.apache.thrift.TProcessor;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.transport.TTransport;

public class MapRSecSaslHelper {

  private static class CLIServiceProcessorFactory extends TProcessorFactory {
    private final ThriftCLIService service;
    private final Server saslServer;

    public CLIServiceProcessorFactory(Server saslServer, ThriftCLIService service) {
      super(null);
      this.service = service;
      this.saslServer = saslServer;
    }

    @Override
    public TProcessor getProcessor(TTransport trans) {
      TProcessor sqlProcessor = new TCLIService.Processor<TCLIService.Iface>(service);
      return saslServer.wrapNonAssumingProcessor(sqlProcessor);
    }
  }

  public static TProcessorFactory getProcessorFactory(Server saslServer,
      ThriftCLIService service) {
    return new CLIServiceProcessorFactory (saslServer, service);
  }

  public static TTransport getTransport(final TTransport underlyingTransport, Map<String, String> saslProps)
      throws SaslException {
    try {
      HadoopThriftAuthBridge.Client authBridge =
        ShimLoader.getHadoopThriftAuthBridge().createClientWithConf("CUSTOM");
      return authBridge.createClientTransport(
              null, null, "MAPRSASL", null,
              underlyingTransport, saslProps);
    } catch (IOException e) {
      throw new SaslException("Failed to open client transport", e);
    }
  }
}
