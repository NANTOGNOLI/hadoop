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

/**
 * The Guarded S3A Store.
 * This is where the core operations are implemented; the "APIs", both public
 * (FileSystem, AbstractFileSystem) and the internal ones (WriteOperationHelper)
 * call through here.
 */
public interface RawS3A extends S3AService {

  void bind(EncryptionSecrets encryptionSecrets,
      AWSCredentialProviderList credentials,
      SignerManager signerManager,
      TransferManager transfers,
      AmazonS3 s3client);
}
