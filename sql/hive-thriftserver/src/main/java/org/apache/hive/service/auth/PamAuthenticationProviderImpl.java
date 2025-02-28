/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hive.service.auth;

import com.mapr.login.PasswordAuthentication;

import javax.security.sasl.AuthenticationException;

public class PamAuthenticationProviderImpl implements PasswdAuthenticationProvider {

  PamAuthenticationProviderImpl() {
  }

  @Override
  public void Authenticate(String user, String password) throws AuthenticationException {


    try {
      PasswordAuthentication pam = new PasswordAuthentication();
      if (!pam.authenticate(user, password)) {
        throw new AuthenticationException("Failed authentication for user " + user);
      }
    } catch(Throwable e) {
      // Catch the exception caused by missing jpam.so which otherwise would
      // crashes the thread and causes the client hanging rather than notifying
      // the client nicely
      throw new AuthenticationException(e.toString());
    }
  }
}
