/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.aws.credentials.provider.factory.strategies;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.PropertiesFileCredentialsProvider;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.context.PropertyContext;
import org.apache.nifi.processors.aws.credentials.provider.PropertiesCredentialsProvider;
import org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

import java.io.File;


/**
 * Supports AWS credentials stored in a file.  The file format should be a Java properties file like the following:
 *
 * <code>
 * accessKey = XXXXXXXXXXXXXXXXXXXX
 * secretKey = xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
 * </code>
 *
 *  * @see <a href="http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/PropertiesFileCredentialsProvider.html">
 *     PropertiesFileCredentialsProvider</a>
 */
public class FileCredentialsStrategy extends AbstractCredentialsStrategy {

    public FileCredentialsStrategy() {
        super("Credentials File", new PropertyDescriptor[] {
            AWSCredentialsProviderControllerService.CREDENTIALS_FILE
        });
    }

    @Override
    public AWSCredentialsProvider getCredentialsProvider(final PropertyContext propertyContext) {
        final String credentialsFile = propertyContext.getProperty(AWSCredentialsProviderControllerService.CREDENTIALS_FILE).getValue();
        return new PropertiesFileCredentialsProvider(credentialsFile);
    }

    @Override
    public AwsCredentialsProvider getAwsCredentialsProvider(final PropertyContext propertyContext) {
        final String credentialsFile = propertyContext.getProperty(AWSCredentialsProviderControllerService.CREDENTIALS_FILE).getValue();
        return new PropertiesCredentialsProvider(new File(credentialsFile));
    }

}
