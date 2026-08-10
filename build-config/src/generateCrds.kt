package com.example

import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.ModuleSources
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

@OptIn(ExperimentalPathApi::class)
@TaskAction
fun generateCrds(@Input sourceSet: ModuleSources, @Output generatedSourcesDir: Path) {
    generatedSourcesDir.deleteRecursively()
}
