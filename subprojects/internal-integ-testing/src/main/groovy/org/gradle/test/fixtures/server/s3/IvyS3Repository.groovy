/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.test.fixtures.server.s3
import org.gradle.test.fixtures.ivy.IvyFileRepository
import org.gradle.test.fixtures.ivy.RemoteIvyModule
import org.gradle.test.fixtures.ivy.RemoteIvyRepository

class IvyS3Repository implements RemoteIvyRepository {

    S3StubServer server
    String bucket
    IvyFileRepository backingRepository
    String repositoryPath

    public IvyS3Repository(S3StubServer server, File repoDir, String repositoryPath, String bucket, boolean m2Compatible = false, String dirPattern = null, String ivyFilePattern = null, String artifactFilePattern = null) {
        assert !bucket.startsWith('/')
        this.server = server
        this.bucket = bucket
        this.backingRepository = new IvyFileRepository(repoDir, m2Compatible, dirPattern, ivyFilePattern, artifactFilePattern)
        this.repositoryPath = repositoryPath
    }

    URI getUri() {
        new URI("s3://${bucket}${repositoryPath}")
    }

    @Override
    String getIvyPattern() {
        return "$uri/${backingRepository.baseIvyPattern}"
    }

    @Override
    RemoteIvyModule module(String organisation, String module) {
        return new IvyS3Module(server, backingRepository.module(organisation, module), repositoryPath, bucket)
    }

    @Override
    RemoteIvyModule module(String organisation, String module, Object revision) {
        return new IvyS3Module(server, backingRepository.module(organisation, module, revision), repositoryPath, bucket)
    }

    String getArtifactPattern() {
        return "$uri/${backingRepository.baseArtifactPattern}"
    }

    String getBaseIvyPattern() {
        return backingRepository.baseIvyPattern
    }

    String getBaseArtifactPattern() {
        return backingRepository.baseArtifactPattern
    }
    @Override
    void expectDirectoryList(String organisation, String module) {

    }
}
