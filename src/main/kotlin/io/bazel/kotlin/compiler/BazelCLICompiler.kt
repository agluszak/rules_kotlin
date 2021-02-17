package io.bazel.kotlin.compiler

import io.bazel.kotlin.model.diagnostics.Diagnostics
import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

open class BazelCLICompiler<Args : CommonCompilerArguments>(private val delegate: CLICompiler<Args>) {
  fun exec(errStream: PrintStream, diagnosticsFile: String? = null, vararg args: String): ExitCode {
    val arguments = delegate.createArguments().also { delegate.parseArguments(args, it) }
    val collector =
      MessageCollectorWithDiagnostics(errStream, MessageRenderer.PLAIN_RELATIVE_PATHS, arguments.verbose)
    val exitCode = delegate.exec(collector, Services.EMPTY, arguments)

    diagnosticsFile?.let {
      val path = Paths.get(it)
      collector.writeTo(path)
    }

    return exitCode
  }

  class MessageCollectorWithDiagnostics(errStream: PrintStream,
                                        messageRenderer: MessageRenderer,
                                        verbose: Boolean)
    : PrintingMessageCollector(errStream, messageRenderer, verbose) {
    private val diagnostics = mutableMapOf<String, MutableList<Diagnostics.Diagnostic>>()

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
      super.report(severity, message, location)
      val builder = Diagnostics.Diagnostic
        .newBuilder()
      var path = ""
      location?.let {
        builder.range = convertRange(it)
        path = it.path
      }

      val diagnostic: Diagnostics.Diagnostic = builder
        .setSeverity(convertSeverity(severity))
        .setMessage(message)
        .build()

      this.diagnostics.getOrPut(path) { mutableListOf() }
        .add(diagnostic)
    }

    private fun convertSeverity(severity: CompilerMessageSeverity): Diagnostics.Severity {
      return when (severity) {
        CompilerMessageSeverity.EXCEPTION -> Diagnostics.Severity.ERROR
        CompilerMessageSeverity.ERROR -> Diagnostics.Severity.ERROR
        CompilerMessageSeverity.STRONG_WARNING -> Diagnostics.Severity.WARNING
        CompilerMessageSeverity.WARNING -> Diagnostics.Severity.WARNING
        CompilerMessageSeverity.INFO -> Diagnostics.Severity.INFORMATION
        CompilerMessageSeverity.LOGGING -> Diagnostics.Severity.INFORMATION
        CompilerMessageSeverity.OUTPUT -> Diagnostics.Severity.INFORMATION
      }
    }

    private fun convertRange(location: CompilerMessageSourceLocation): Diagnostics.Range {
      return Diagnostics.Range
        .newBuilder()
        .setStart(
          Diagnostics.Position
            .newBuilder()
            .setLine(location.line - 1)
            .setCharacter(location.column - 1)
            .build()
        )
        .build()
    }

    fun writeTo(path: Path) {
      val targetDiagnostics = Diagnostics.TargetDiagnostics.newBuilder()

      targetDiagnostics.addAllDiagnostics(
        diagnostics.map { (path, diagnostics) ->
          Diagnostics.FileDiagnostics
            .newBuilder()
            .setPath(path)
            .addAllDiagnostics(diagnostics)
            .build()
        })

      Files.write(path, targetDiagnostics.build().toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
  }
}

@Suppress("unused")
class BazelK2JSCompiler : BazelCLICompiler<K2JSCompilerArguments>(K2JSCompiler())

@Suppress("unused")
class BazelK2JVMCompiler : BazelCLICompiler<K2JVMCompilerArguments>(K2JVMCompiler())
