package bloop.engine.caches

import java.util.Optional

import bloop.{Compiler, Project}
import bloop.Compiler.Result
import bloop.engine.{Build, ExecutionContext}
import bloop.io.AbsolutePath
import bloop.logging.Logger
import bloop.reporter.Reporter
import monix.eval.Task
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
 * Maps projects to compilation results, populated by `Tasks.compile`.
 *
 * The results cache has two important goals:
 *   1. Keep track of the last compilation results, no matter what results those were.
 *   2. Keep track of the last successful incremental results per project, so that they
 *      can be used by `compile` to locate the last good analysis file.
 *
 * This data structure is not thread-safe and should not be used like so.
 *
 * @param all The map of projects to latest compilation results.
 * @param succesful The map of all projects to latest successful compilation results.
 * @param logger A logger.
 */
final class ResultsCache private (
    all: Map[Project, Compiler.Result],
    successful: Map[Project, PreviousResult],
    logger: Logger
) {

  /** Returns the last succesful result if present, empty otherwise. */
  def lastSuccessfulResult(project: Project): PreviousResult =
    successful.getOrElse(project, ResultsCache.EmptyResult)

  /** Returns the latest compilation result if present, empty otherwise. */
  def latestResult(project: Project): Compiler.Result =
    all.getOrElse(project, Result.Empty)

  /** Diff the latest changed projects between `cache` and `this`. */
  def diffLatest(cache: ResultsCache): List[(Project, Compiler.Result)] = {
    cache.allResults.collect { case t @ (p, r) if this.latestResult(p) != r => t }.toList
  }

  def allResults: Iterator[(Project, Compiler.Result)] = all.iterator
  def allSuccessful: Iterator[(Project, PreviousResult)] = successful.iterator
  def cleanSuccessful(projects: List[Project]): ResultsCache = {
    // Remove all the successful results from the cache.
    val newSuccessful = successful.filterKeys(p => !projects.contains(p))
    new ResultsCache(all, newSuccessful, logger)
  }

  def addResult(project: Project, result: Compiler.Result): ResultsCache = {
    val newAll = all + (project -> result)
    result match {
      case s: Compiler.Result.Success =>
        new ResultsCache(newAll, successful + (project -> s.previous), logger)
      case r => new ResultsCache(newAll, successful, logger)
    }
  }

  def addResults(ps: List[(Project, Compiler.Result)]): ResultsCache =
    ps.foldLeft(this) { case (rs, (p, r)) => rs.addResult(p, r) }

  override def toString: String = s"ResultsCache(${successful.mkString(", ")})"
}

object ResultsCache {
  import java.util.concurrent.ConcurrentHashMap

  // TODO: Use a guava cache that stores maximum 200 analysis file
  private[bloop] val persisted = ConcurrentHashMap.newKeySet[PreviousResult]()

  private[ResultsCache] final val EmptyResult: PreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  def load(build: Build, cwd: AbsolutePath, logger: Logger): ResultsCache = {
    val handle = loadAsync(build, cwd, logger).runAsync(ExecutionContext.ioScheduler)
    Await.result(handle, Duration.Inf)
  }

  def loadAsync(build: Build, cwd: AbsolutePath, logger: Logger): Task[ResultsCache] = {
    import java.nio.file.Files
    import sbt.internal.inc.FileAnalysisStore
    import bloop.util.JavaCompat.EnrichOptional

    def fetchPreviousResult(p: Project): Task[Compiler.Result] = {
      val analysisFile = ResultsCache.pathToAnalysis(p)
      if (Files.exists(analysisFile.underlying)) {
        Task {
          val contents = FileAnalysisStore.binary(analysisFile.toFile).get().toOption
          contents match {
            case Some(res) =>
              logger.debug(s"Loading previous analysis for '${p.name}' from '$analysisFile'.")
              val r = PreviousResult.of(Optional.of(res.getAnalysis), Optional.of(res.getMiniSetup))
              val reporter = Reporter.fromAnalysis(res.getAnalysis, cwd, logger)
              Result.Success(reporter, r, 0L)
            case None =>
              logger.debug(s"Analysis '$analysisFile' for '${p.name}' is empty.")
              Result.Empty
          }

        }
      } else {
        Task.now {
          logger.debug(s"Missing analysis file for project '${p.name}'")
          Result.Empty
        }
      }
    }

    val all = build.projects.map(p => fetchPreviousResult(p).map(r => p -> r))
    Task.gatherUnordered(all).executeOn(ExecutionContext.ioScheduler).map { projectResults =>
      val cache = new ResultsCache(Map.empty, Map.empty, logger)
      cache.addResults(projectResults)
    }
  }

  def pathToAnalysis(project: Project): AbsolutePath =
    project.out.resolve(s"${project.name}-analysis.bin")
}
