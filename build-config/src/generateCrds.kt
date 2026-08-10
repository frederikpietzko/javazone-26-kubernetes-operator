package com.example

import io.fabric8.crd.generator.collector.CustomResourceCollector
import io.fabric8.crdv2.generator.CRDGenerator
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively

@OptIn(ExperimentalPathApi::class)
@TaskAction
fun generateCrds(
    @Input compilationArtifact: CompilationArtifact,
    @Input compileClasspath: Classpath,
    @Output outputDir: Path,
) {
    outputDir.deleteRecursively()
    outputDir.createDirectories()

    val compiledArtifact = compilationArtifact.artifact
    val classpathElements = (compileClasspath.resolvedFiles + compiledArtifact)
        .distinct()
        .map(Path::toString)

    val collector = CustomResourceCollector()
        .withParentClassLoader(Thread.currentThread().contextClassLoader)
        .withClasspathElements(classpathElements)
        .withFilesToScan(listOf(compiledArtifact.toFile()))

    val customResourceClasses = collector.findCustomResourceClasses()
    check(customResourceClasses.isNotEmpty()) {
        "No Fabric8 custom resources found in ${compiledArtifact.fileName}"
    }

    val generationInfo = CRDGenerator()
        .customResourceClasses(customResourceClasses)
        .inOutputDir(outputDir.toFile())
        .detailedGenerate()

    generationInfo.crdDetailsPerNameAndVersion.forEach { (crdName, versionToInfo) ->
        println("Generated CRD $crdName:")
        versionToInfo.forEach { (version, info) ->
            println(" $version -> ${info.filePath}")
        }
    }
}
