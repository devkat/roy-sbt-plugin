package net.devkat.sbtroy

import sbt._
import sbt.Keys._
import sbt.Project.Initialize
import java.util.regex.Pattern

object RoyPlugin extends Plugin {
  
  object RoyKeys {
    val roy = TaskKey[Seq[File]]("roy", "Compile Roy sources")
    val royIncludeFilter = SettingKey[FileFilter]("roy-include-filter", "Include filter")
    val royExcludeFilter = SettingKey[Seq[FileFilter]]("roy-exclude-filter", "Exclude filter")
    val royCompilerOptions = SettingKey[Seq[String]]("roy-compiler-options", "Options for the Roy compiler")
  }

  import RoyKeys._

  def time[T](out: TaskStreams, msg: String)(func: => T): T = {
    val startTime = java.lang.System.currentTimeMillis
    val result = func
    val endTime = java.lang.System.currentTimeMillis
    out.log.debug("TIME sbt-roy " + msg + ": " + (endTime - startTime) + "ms")
    result
  }
  
  class PathFilter(pattern:String) extends FileFilter {
    lazy val regex = pattern
        .replace("**", "(.+)")
        .replace("*", "(^\\/+)")
        .replace("/", "\\/")
    lazy val compiledPattern = Pattern.compile(regex)
    override def accept(file:File) =
      compiledPattern.matcher(file.getAbsolutePath()).matches()
  }
  
  class CompositeFilter(filters: Seq[FileFilter]) extends FileFilter {
    override def accept(file:File) =
      filters.foldLeft(false)((accept, filter) => accept || filter.accept(file))
  }

  def sourcesTask: Initialize[Task[Seq[File]]] =
    (streams, sourceDirectories in roy, royIncludeFilter in roy, royExcludeFilter in roy) map {
      (out, sourceDirs, inFilter, exFilters) =>
        time(out, "sourcesTask") {
          val exFilter = new CompositeFilter(exFilters)
          sourceDirs.foldLeft(Seq[File]()) {
            (accum, sourceDir) =>
              accum ++ sourceDir.descendantsExcept(inFilter, exFilter).get
          }
        }
    }

  def compileTask =
    (streams, sourceDirectory in roy, sources in roy, resourceManaged in roy, royCompilerOptions in roy) map {
      (out, sourceDir, sources, targetDir, compilerOptions) =>
        time(out, "compileTask") {
          out.log.info("Compiling Roy sources")
          sources map (file => {
            out.log.info("Compiling " + file)
            RoyCompiler.compile(file, sourceDir, targetDir, compilerOptions)
          })
        }
    }

  def roySettingsIn(conf: Configuration) =
    inConfig(conf)(Seq(
      royIncludeFilter             :=   "*.roy" || "*.royl",
      royExcludeFilter             :=   Seq((".*" - ".") || "_*" || HiddenFileFilter),
      sourceDirectory in roy       <<=  (sourceDirectory in conf),
      sourceDirectories in roy     <<=  (sourceDirectory in (conf, roy)) { Seq(_) },
      sources in roy               <<=  sourcesTask,
      unmanagedResources in conf   <++= sources,
      resourceManaged in roy       <<=  (resourceManaged in conf),
      royCompilerOptions           :=   Seq(),
      roy                          <<=  compileTask,
      resourceGenerators           <+=  roy
    )) ++ Seq(
      watchSources                 <++= (sources in roy in conf)
    )

  def roySettings: Seq[Setting[_]] =
    roySettingsIn(Compile) ++
    roySettingsIn(Test)

}

case class CompilationException(message: String, royFile: File, atLine: Option[Int])
    extends Exception("Compilation error: " + message) {
  def line = atLine
  def position = None
  def input = Some(scalax.file.Path(royFile))
  def sourceName = Some(royFile.getAbsolutePath)
}

object RoyCompiler {

  import java.io._

  import org.mozilla.javascript._
  import org.mozilla.javascript.tools.shell._

  import scala.collection.JavaConverters._

  import scalax.file._

  private lazy val compiler = {
    val ctx = Context.enter; ctx.setOptimizationLevel(-1)
    val global = new Global; global.init(ctx)
    val scope = ctx.initStandardObjects(global)

    val wrappedRoyCompiler = Context.javaToJS(this, scope)
    ScriptableObject.putProperty(scope, "RoyCompiler", wrappedRoyCompiler)
    //ScriptableObject.putProperty(scope, "console", Context.javaToJS(JsConsole, scope))

    ctx.evaluateReader(scope, new InputStreamReader(
      this.getClass.getClassLoader.getResource("roy.js").openConnection().getInputStream()),
      "roy.js",
      1, null)

    val roy = scope.get("roy", scope).asInstanceOf[NativeObject]
    val compilerFunction = roy.get("compile", scope).asInstanceOf[Function]

    Context.exit

    (source: File, sourceDir: File, targetDir: File) => {
      System.out.println("Compiling file " + source.getPath() + " to " + targetDir)
      val royCode = Path(source).string.replace("\r", "")
      val compiledObject = Context.call(null, compilerFunction, scope, scope, Array(royCode)).asInstanceOf[NativeObject]
      val output = compiledObject.get("output", scope).asInstanceOf[String]
      
      val srcPath = source.getAbsolutePath
      val basePath = sourceDir.getAbsolutePath
      if (!srcPath.startsWith(basePath)) {
        throw new Exception(srcPath + " is not located in " + basePath)
      }
      val relPath = srcPath.substring(basePath.length()).replaceAll("^\\/", "").replaceAll("\\.roy$", ".js")
      val outFile:Path = Path(targetDir) / (relPath, '/')
      outFile.write(output)
      outFile.fileOption.get
    }
    
  }

  def compile(source: File, sourceDir: File, targetDir: File, options: Seq[String]): File = {
    try {
      compiler(source, sourceDir, targetDir)
    } catch {
      case e: JavaScriptException => {

        val line = """.*on line ([0-9]+).*""".r
        val error = e.getValue.asInstanceOf[Scriptable]

        throw ScriptableObject.getProperty(error, "message").asInstanceOf[String] match {
          case msg @ line(l) => CompilationException(msg, source, Some(Integer.parseInt(l)))
          case msg => CompilationException(msg, source, None)
        }

      }
    }
  }

}
