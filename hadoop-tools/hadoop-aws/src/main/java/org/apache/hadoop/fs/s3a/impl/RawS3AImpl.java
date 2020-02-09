/*
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

package org.apache.hadoop.fs.s3a.impl;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.transfer.TransferManager;

import org.apache.hadoop.fs.s3a.AWSCredentialProviderList;
import org.apache.hadoop.fs.s3a.auth.SignerManager;
import org.apache.hadoop.fs.s3a.auth.delegation.EncryptionSecrets;

public class RawS3AImpl extends AbstractS3AService implements RawS3A {

  private EncryptionSecrets encryptionSecrets;

  private AWSCredentialProviderList credentials;

  private SignerManager signerManager;

  private TransferManager transfers;

  private AmazonS3 s3client;

  public RawS3AImpl(final String name) {
    super(name);
  }

  public RawS3AImpl() {
    this("raws3a");
  }

  @Override
  public void bind(final EncryptionSecrets encryptionSecrets,
      final AWSCredentialProviderList credentials,
      final SignerManager signerManager,
      final TransferManager transfers,
      final AmazonS3 s3client) {

    this.encryptionSecrets = encryptionSecrets;
    this.credentials = credentials;
    this.signerManager = signerManager;
    this.transfers = transfers;
    this.s3client = s3client;
  }
}
