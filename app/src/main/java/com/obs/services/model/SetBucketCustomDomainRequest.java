/**
 * Copyright 2019 Huawei Technologies Co.,Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */


package com.obs.services.model;

public class SetBucketCustomDomainRequest extends BaseBucketRequest {

    {
        httpMethod = HttpMethodEnum.PUT;
    }

    private String domainName;
    
    public SetBucketCustomDomainRequest() {

    }

    private CustomDomainCertificateConfig customDomainCertificateConfig;

    public SetBucketCustomDomainRequest(String bucketName, String domainName) {
        this.bucketName = bucketName;
        this.domainName = domainName;
    }

    public SetBucketCustomDomainRequest(String bucketName,
                                        String domainName,
                                        CustomDomainCertificateConfig customDomainCertificateConfig) {
        this.bucketName = bucketName;
        this.domainName = domainName;
        this.customDomainCertificateConfig = customDomainCertificateConfig;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public CustomDomainCertificateConfig getCustomDomainCertificateConfig() {
        return customDomainCertificateConfig;
    }

    public void setCustomDomainCertificateConfig(CustomDomainCertificateConfig customDomainCertificateConfig) {
        this.customDomainCertificateConfig = customDomainCertificateConfig;
    }

    @Override
    public String toString() {
        return "SetBucketCustomDomainRequest [domainName=" + domainName + ", bucketName=" + getBucketName() + "]";
    }
}
