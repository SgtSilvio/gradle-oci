package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.internal.OciExtensionImpl
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create

/**
 * @author Silvio Giebl
 */
class OciPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ociExtension = project.extensions.create(OciExtension::class, "oci", OciExtensionImpl::class)
//
//        ociExtension.images.all {
//            val imageName = name
//            project.tasks.register<OciConfigTask>("${imageName}OciImage") { // mainOciImage
//
//            }
//            // TODO move into constructor?
//            layers.whenObjectAdded {
//                index.set(layers.size)
//                project.tasks.register<OciConfigTask>("${imageName}${name.capitalize()}OciLayer") { // mainApplicationOciLayer
//                }
//            }
//        }

        /*
        oci {
            images {
                register("main") {
                    baseImage.set()
                    platforms {
                        platform {
                            architecture.set("amd64")
                            os.set("linux")
                        }
                        platform {
                            architecture.set("arm64")
                            os.set("linux")
                            variant.set("v8")
                        }
                    }
                    config {
                        author.set("HiveMQ")
                        user.set("1000:1000")
                        ports.addAll("1883", "8080")
                        environment.put("HIVEMQ_WABERN", "true")
                        environment.put("HIVEMQ_ALLOW_ALL_CLIENTS", "false")
                        entryPoint.addAll("java", "-jar", "/opt/hivemq/bin/hivemq.jar")
                        arguments.addAll("start", "--fast")
                        volumes.addAll("/opt/hivemq/data", "/opt/hivemq/logs")
                        workingDirectory.set("/opt/hivemq")
                    }
                    layers {
                        register("hivemq") {
                            from(tasks.jar) {
                                into("/opt/hivemq/bin")
                                permissions.set(0b111_111_000)
                                userId.set(1234)
                                groupId.set(1234)
                                eachEntry {
                                    permissions = 0b111_111_000
                                    userId = 1234
                                    groupId = 1234
                                }
                            }

                            addFiles(tasks.jar, "/opt/hivemq/bin")

                            from()
                            from()
                            into("root")
                            child {
                                from()
                                from()
                                into("data")
                                rename()
                            }

                            from()
                            from()
                            into("root")
                            into("data") {
                                from()
                                from()
                                rename()
                            }

                            from()
                            from()
                            into("root")
                            from() {
                                from()
                                into("data")
                                rename()
                            }

                            from() {
                                rename()
                            }

                            into("") {
                                from()
                                rename()
                            }

                            child {
                                from()
                                rename()
                            }

                            into("opt/hivemq") {
                                from(tasks.jar) {
                                    into("bin")
                                }
                            }
                        }
                    }
                }
            }
        }
         */

        /*
        oci {
            imageDefinitions {
                register("main") {
                    capabilities {

                    }
                    allPlatforms {
                        parentImages.add("org.example:example")
                        parentImages {
                            add(project()) {
                                capabilities {
                                    requireCapability("org.example:example")
                                }
                            }
                            add(project("sub"))
                            add(module("org.example:example"))
                            add("org.example:example")
                            add(module(libs.example))
                            add(libs.example)
                        }

                        user.set(10000)
                        entryPoint.set(listOf("/opt/docker-entrypoint.sh"))
                        arguments.set(listOf("/opt/hivemq/bin/run.sh"))
                        workingDirectory.set("/opt/hivemq")
                        ports.addAll(1883, 8000, 8080)
                        volumes.addAll("/opt/hivemq/data", "/opt/hivemq/log")
                        environment.put("LANG", "en_US.UTF-8")

                        layers {
                            layer("hivemqServerApplication") {
                                createdBy.set("wabern")
                                contents {
                                    from()
                                    into("opt/hivemq")
                                }
                            }
                            layer("hivemqServerConfig") {
                                createdBy.set("wabern")
                                contents {
                                    from("wabern/config.xml")
                                    into("opt/hivemq/conf")
                                }
                            }
                        }
                    }
                    addPlatform(platform("linux", "amd64"))
                    addPlatform(platform("linux", "arm64", "v8")) {
                        environment.put("ARM", "true")
                    }
                    platformsMatching({ it.os == "linux" }) {
                        environment.put("LINUX", "true")
                    }
                }
            }
        }
         */
    }
}