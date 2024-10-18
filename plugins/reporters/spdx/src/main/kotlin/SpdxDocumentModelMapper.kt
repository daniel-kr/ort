/*
 * Copyright (C) 2020 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.plugins.reporters.spdx

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.SourceCodeOrigin.ARTIFACT
import org.ossreviewtoolkit.model.SourceCodeOrigin.VCS
import org.ossreviewtoolkit.model.licenses.LicenseInfoResolver
import org.ossreviewtoolkit.reporter.LicenseTextProvider
import org.ossreviewtoolkit.utils.ort.Environment
import org.ossreviewtoolkit.utils.ort.ORT_NAME
import org.ossreviewtoolkit.utils.spdx.SpdxConstants
import org.ossreviewtoolkit.utils.spdx.SpdxLicense
import org.ossreviewtoolkit.utils.spdx.model.SpdxCreationInfo
import org.ossreviewtoolkit.utils.spdx.model.SpdxDocument
import org.ossreviewtoolkit.utils.spdx.model.SpdxFile
import org.ossreviewtoolkit.utils.spdx.model.SpdxPackage
import org.ossreviewtoolkit.utils.spdx.model.SpdxRelationship

/**
 * A class for mapping [OrtResult]s to [SpdxDocument]s.
 */
internal object SpdxDocumentModelMapper {
    data class SpdxDocumentParams(
        val documentName: String,
        val documentComment: String,
        val creationInfoComment: String,
        val creationInfoPerson: String,
        val creationInfoOrganization: String,
        val fileInformationEnabled: Boolean
    )

    fun map(
        ortResult: OrtResult,
        licenseInfoResolver: LicenseInfoResolver,
        licenseTextProvider: LicenseTextProvider,
        params: SpdxDocumentParams
    ): SpdxDocument {
        val nextFileIndex = AtomicInteger(1)
        val packages = mutableListOf<SpdxPackage>()
        val relationships = mutableListOf<SpdxRelationship>()
        val files = mutableListOf<SpdxFile>()

        val projects = ortResult.getProjects(omitExcluded = true, includeSubProjects = false).sortedBy { it.id }
        val projectPackages = projects.map { project ->

            val filesForProject = if (params.fileInformationEnabled) {
                ortResult.getSpdxFiles(project.id, licenseInfoResolver, VCS, nextFileIndex)
            } else {
                emptyList()
            }

            val spdxProjectPackage = project.toPackage().toSpdxPackage(
                SpdxPackageType.PROJECT,
                licenseInfoResolver,
                ortResult
            ).copy(hasFiles = filesForProject.map { it.spdxId })

            ortResult.getDependencies(
                id = project.id,
                maxLevel = 1,
                omitExcluded = true
            ).mapTo(relationships) { dependency ->
                SpdxRelationship(
                    spdxElementId = spdxProjectPackage.spdxId,
                    relationshipType = SpdxRelationship.Type.DEPENDS_ON,
                    relatedSpdxElement = dependency.toSpdxId()
                )
            }

            files += filesForProject
            spdxProjectPackage
        }


        ortResult.getPackages(omitExcluded = true).sortedBy { it.metadata.id }.forEach { curatedPackage ->
            val pkg = curatedPackage.metadata

            val filesForPackage = if (params.fileInformationEnabled) {
                //TODO: Use SourceCodeOrigin depending on the provenance
                ortResult.getSpdxFiles(pkg.id, licenseInfoResolver, ARTIFACT, nextFileIndex)
            } else {
                emptyList()
            }

            var spdxPackage: SpdxPackage? = null
            if(pkg.vcsProcessed.url.isNotBlank()) {
                spdxPackage = pkg.toSpdxPackage(
                    SpdxPackageType.VCS_PACKAGE,
                    licenseInfoResolver,
                    ortResult
                ).copy(hasFiles = filesForPackage.map { it.spdxId })
            }

            if(pkg.sourceArtifact.url.isNotBlank() && (spdxPackage == null || !spdxPackage.filesAnalyzed)) {
                //Prefers source artifact over VCS. If VCS has not files, we take source artifact anyway.
                //It is unlikely, that both have files.
                spdxPackage = pkg.toSpdxPackage(
                    SpdxPackageType.SOURCE_PACKAGE,
                    licenseInfoResolver,
                    ortResult
                ).copy(hasFiles = filesForPackage.map { it.spdxId })
            }

            if(spdxPackage == null) {
                //If there is neither VCS package nor source artifact, fall back to binary package
                spdxPackage = pkg.toSpdxPackage(
                    SpdxPackageType.BINARY_PACKAGE,
                    licenseInfoResolver,
                    ortResult
                )
            }

            files += filesForPackage
            packages += spdxPackage

            ortResult.getDependencies(
                id = pkg.id,
                maxLevel = 1,
                omitExcluded = true
            ).mapTo(relationships) { dependency ->
                SpdxRelationship(
                    spdxElementId = spdxPackage.spdxId,
                    relationshipType = SpdxRelationship.Type.DEPENDS_ON,
                    relatedSpdxElement = dependency.toSpdxId()
                )
            }
        }

        val creators = listOfNotNull(
            params.creationInfoPerson.takeUnless { it.isEmpty() }?.let { "${SpdxConstants.PERSON} $it" },
            params.creationInfoOrganization.takeUnless { it.isEmpty() }?.let { "${SpdxConstants.ORGANIZATION} $it" },
            "${SpdxConstants.TOOL} $ORT_NAME-${Environment.ORT_VERSION}"
        )

        return SpdxDocument(
            comment = params.documentComment,
            creationInfo = SpdxCreationInfo(
                comment = params.creationInfoComment,
                created = Instant.now().truncatedTo(ChronoUnit.SECONDS),
                creators = creators,
                licenseListVersion = SpdxLicense.LICENSE_LIST_VERSION.substringBefore("-")
            ),
            documentNamespace = "spdx://${UUID.randomUUID()}",
            documentDescribes = projectPackages.map { it.spdxId },
            name = params.documentName,
            packages = projectPackages + packages,
            relationships = relationships.sortedBy { it.spdxElementId },
            files = files
        ).addExtractedLicenseInfo(licenseTextProvider)
    }
}
