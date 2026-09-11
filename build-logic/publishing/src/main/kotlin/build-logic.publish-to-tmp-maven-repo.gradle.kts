import org.gradle.api.publish.plugins.PublishingPlugin
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

plugins {
    id("java-library")
    id("maven-publish")
}

val localRepoElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    description =
        "Shares local maven repository directory that contains the artifacts produced by the current project"
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named("maven-repository"))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

val localRepoDir = layout.buildDirectory.dir("local-maven-repo")

// oss.sonatype.org (legacy OSSRH) is decommissioned and now answers every request with
// HTTP 402. Sonatype's drop-in replacement for the old staging API lives at the host
// below. Override with OSSRH_STAGING_API if it ever moves again.
//
// Both the deploy repository and the promotion request are derived from this single value so
// they cannot drift apart. Blank is treated as unset: CI passes an undefined secret through as
// an empty string, and an empty URL would otherwise resolve to a local directory, making a
// release silently publish nothing.
val ossrhStagingApi = System.getenv("OSSRH_STAGING_API")?.takeIf { it.isNotBlank() }
    ?: "https://ossrh-staging-api.central.sonatype.com"

publishing {
    repositories {
        maven {
            name = "tmp-maven"
            url = uri(localRepoDir)
        }
        maven {
            name = "remote"
            url = uri("$ossrhStagingApi/service/local/staging/deploy/maven2/")
            credentials {
                username = System.getenv("OSSRH_USERNAME")
                password = System.getenv("OSSRH_PASSWORD")
            }
        }
    }
}

localRepoElements.outgoing.artifact(localRepoDir) {
    builtBy(tasks.named("publishAllPublicationsToTmp-mavenRepository"))
}

val cleanLocalRepository by tasks.registering(Delete::class) {
    description = "Clears local-maven-repo so timestamp-based snapshot artifacts do not consume space"
    delete(localRepoDir)
}

tasks.withType<PublishToMavenRepository>()
    .matching { it.name.endsWith("PublicationToTmp-mavenRepository") }
    .configureEach {
        dependsOn(cleanLocalRepository)
    }

// Uploading to the staging API only leaves an *open* staging repository behind; it does not
// reach the Central Portal on its own. This hands it over, after which the deployment shows up
// at https://central.sonatype.com/publishing/deployments.
//
// Note the credentials must be a Central Portal user token. The staging API still accepts legacy
// OSSRH tokens when deploying artifacts, but rejects them here with a 401.
val promoteStagingRepository by tasks.registering {
    description = "Hands the open OSSRH staging repository over to the Central Portal"
    group = PublishingPlugin.PUBLISH_TASK_GROUP

    val namespace = providers.gradleProperty("centralNamespace")
        .orElse(provider { project.group.toString() })
    // "user_managed" leaves the release to Maven Central as a manual step in the Portal UI.
    // Pass -PcentralPublishingType=automatic to release as soon as validation passes.
    val publishingType = providers.gradleProperty("centralPublishingType").orElse("user_managed")
    val username = providers.environmentVariable("OSSRH_USERNAME")
    val password = providers.environmentVariable("OSSRH_PASSWORD")

    doLast {
        val user = username.orNull
            ?: throw GradleException("OSSRH_USERNAME is not set, cannot promote the staging repository")
        val secret = password.orNull
            ?: throw GradleException("OSSRH_PASSWORD is not set, cannot promote the staging repository")
        val bearer = Base64.getEncoder().encodeToString("$user:$secret".toByteArray())

        val endpoint = "$ossrhStagingApi/manual/upload/defaultRepository/${namespace.get()}" +
            "?publishing_type=${publishingType.get()}"
        logger.lifecycle("Promoting staging repository for ${namespace.get()} to the Central Portal")

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        val (code, body) = try {
            connection.requestMethod = "POST"
            // HttpURLConnection silently downgrades a redirected POST to a GET, which would
            // report success while promoting nothing. Surface 3xx as a failure instead.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.connectTimeout = 60_000
            connection.readTimeout = 15 * 60_000
            val status = connection.responseCode
            val stream = if (status < HttpURLConnection.HTTP_BAD_REQUEST) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            status to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }

        if (code !in 200..299) {
            throw GradleException(
                "Could not promote the staging repository for ${namespace.get()}. " +
                    "Received status code $code from $ossrhStagingApi${if (body.isBlank()) "" else ": $body"}"
            )
        }
        logger.lifecycle("Staging repository handed over, review it at https://central.sonatype.com/publishing/deployments")
    }
}

val remotePublish = tasks.named("publishAllPublicationsToRemoteRepository")

// The task above is a lifecycle aggregate: it never fails itself, only the per-publication
// upload tasks below do. Those are what the promotion has to be gated on.
val remoteUploads = tasks.withType<PublishToMavenRepository>()
    .matching { it.repository?.name == "remote" }

val publishToCentralPortal by tasks.registering {
    description = "Publishes to the OSSRH staging repository and hands it over to the Central Portal"
    group = PublishingPlugin.PUBLISH_TASK_GROUP
    dependsOn(remotePublish)
    dependsOn(promoteStagingRepository)
}

promoteStagingRepository {
    mustRunAfter(remotePublish)
    // mustRunAfter only orders the tasks. Under --continue Gradle keeps going after a failure,
    // so without this an upload that failed halfway would still be promoted to the Portal.
    onlyIf {
        val failed = remoteUploads.filter { it.state.failure != null }
        if (failed.isNotEmpty()) {
            logger.lifecycle(
                "Skipping promotion because the upload failed: " + failed.joinToString { it.name }
            )
        }
        failed.isEmpty()
    }
}
