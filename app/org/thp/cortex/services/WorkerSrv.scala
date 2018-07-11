package org.thp.cortex.services

import java.nio.file.{ Files, Path, Paths }
import javax.inject.{ Inject, Singleton }

import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

import play.api.libs.json.{ JsObject, JsString }
import play.api.{ Configuration, Logger }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import org.thp.cortex.models._

import org.elastic4play._
import org.elastic4play.controllers.{ Fields, StringInputValue }
import org.elastic4play.services._
import org.scalactic._
import org.scalactic.Accumulation._

import org.elastic4play.database.ModifyConfig

@Singleton
class WorkerSrv(
    analyzersPaths: Seq[Path],
    workersPaths: Seq[Path],
    workerModel: WorkerModel,
    organizationSrv: OrganizationSrv,
    userSrv: UserSrv,
    createSrv: CreateSrv,
    getSrv: GetSrv,
    updateSrv: UpdateSrv,
    deleteSrv: DeleteSrv,
    findSrv: FindSrv,
    implicit val ec: ExecutionContext,
    implicit val mat: Materializer) {

  @Inject() def this(
      config: Configuration,
      analyzerModel: WorkerModel,
      organizationSrv: OrganizationSrv,
      userSrv: UserSrv,
      createSrv: CreateSrv,
      getSrv: GetSrv,
      updateSrv: UpdateSrv,
      deleteSrv: DeleteSrv,
      findSrv: FindSrv,
      ec: ExecutionContext,
      mat: Materializer) = this(
    config.get[Seq[String]]("analyzer.path").map(p ⇒ Paths.get(p)),
    config.get[Seq[String]]("responder.path").map(p ⇒ Paths.get(p)),
    analyzerModel,
    organizationSrv,
    userSrv,
    createSrv,
    getSrv,
    updateSrv,
    deleteSrv,
    findSrv,
    ec,
    mat)

  private lazy val logger = Logger(getClass)
  private var workerMap = Map.empty[String, WorkerDefinition]

  private object workerMapLock

  rescan()

  def getDefinition(workerId: String): Future[WorkerDefinition] = workerMap.get(workerId) match {
    case Some(worker) ⇒ Future.successful(worker)
    case None         ⇒ Future.failed(NotFoundError(s"Worker $workerId not found"))
  }

  //  def listDefinitions: (Source[WorkerDefinition, NotUsed], Future[Long]) = Source(workerMap.values.toList) → Future.successful(workerMap.size.toLong)

  def listAnalyzerDefinitions: (Source[WorkerDefinition, NotUsed], Future[Long]) = {
    val analyzerDefinitions = workerMap.values.filter(_.tpe == WorkerType.analyzer)
    Source(analyzerDefinitions.toList) → Future.successful(analyzerDefinitions.size.toLong)
  }

  def listResponderDefinitions: (Source[WorkerDefinition, NotUsed], Future[Long]) = {
    val analyzerDefinitions = workerMap.values.filter(_.tpe == WorkerType.responder)
    Source(analyzerDefinitions.toList) → Future.successful(analyzerDefinitions.size.toLong)
  }

  def get(workerId: String): Future[Worker] = getSrv[WorkerModel, Worker](workerModel, workerId)

  def getForUser(userId: String, workerId: String): Future[Worker] = {
    userSrv.getOrganizationId(userId)
      .flatMap(organization ⇒ getForOrganization(organization, workerId))
  }

  def getForOrganization(organizationId: String, workerId: String): Future[Worker] = {
    import org.elastic4play.services.QueryDSL._
    find(
      and(withParent("organization", organizationId), withId(workerId)),
      Some("0-1"), Nil)._1
      .runWith(Sink.headOption)
      .map(_.getOrElse(throw NotFoundError(s"worker $workerId not found")))
  }

  //  private def listForOrganization(organizationId: String): (Source[Worker, NotUsed], Future[Long]) = {
  //    import org.elastic4play.services.QueryDSL._
  //    findForOrganization(organizationId, any, Some("all"), Nil)
  //  }
  //
  //  def listAnalyzerForOrganization(organizationId: String): (Source[Worker, NotUsed], Future[Long]) = {
  //    import org.elastic4play.services.QueryDSL._
  //    findForOrganization(organizationId, "type" ~= WorkerType.analyzer, Some("all"), Nil)
  //  }
  //
  //  private def listForUser(userId: String): (Source[Worker, NotUsed], Future[Long]) = {
  //    import org.elastic4play.services.QueryDSL._
  //    findForUser(userId, any, Some("all"), Nil)
  //  }
  //
  //  def listAnalyzerForUser(userId: String): (Source[Worker, NotUsed], Future[Long]) = {
  //    import org.elastic4play.services.QueryDSL._
  //    findForUser(userId, "type" ~= WorkerType.analyzer, Some("all"), Nil)
  //  }
  //
  //  def findForUser(userId: String, queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[Worker, NotUsed], Future[Long]) = {
  //    val workers = for {
  //      user ← userSrv.get(userId)
  //      organizationId = user.organization()
  //    } yield findForOrganization(organizationId, queryDef, range, sortBy)
  //    val analyserSource = Source.fromFutureSource(workers.map(_._1)).mapMaterializedValue(_ ⇒ NotUsed)
  //    val analyserTotal = workers.flatMap(_._2)
  //    analyserSource → analyserTotal
  //  }

  def findAnalyzersForUser(userId: String, queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[Worker, NotUsed], Future[Long]) = {
    import org.elastic4play.services.QueryDSL._
    val analyzers = for {
      user ← userSrv.get(userId)
      organizationId = user.organization()
    } yield findForOrganization(organizationId, and(queryDef, "type" ~= WorkerType.analyzer), range, sortBy)
    val analyserSource = Source.fromFutureSource(analyzers.map(_._1)).mapMaterializedValue(_ ⇒ NotUsed)
    val analyserTotal = analyzers.flatMap(_._2)
    analyserSource → analyserTotal
  }

  def findRespondersForUser(userId: String, queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[Worker, NotUsed], Future[Long]) = {
    import org.elastic4play.services.QueryDSL._
    val analyzers = for {
      user ← userSrv.get(userId)
      organizationId = user.organization()
    } yield findForOrganization(organizationId, and(queryDef, "type" ~= WorkerType.responder), range, sortBy)
    val analyserSource = Source.fromFutureSource(analyzers.map(_._1)).mapMaterializedValue(_ ⇒ NotUsed)
    val analyserTotal = analyzers.flatMap(_._2)
    analyserSource → analyserTotal
  }

  private def findForOrganization(organizationId: String, queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[Worker, NotUsed], Future[Long]) = {
    import org.elastic4play.services.QueryDSL._
    find(and(withParent("organization", organizationId), queryDef), range, sortBy)
  }

  private def find(queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[Worker, NotUsed], Future[Long]) = {
    findSrv[WorkerModel, Worker](workerModel, queryDef, range, sortBy)
  }

  def rescan(): Unit = {
    scan(analyzersPaths.map(_ → WorkerType.analyzer) ++
      workersPaths.map(_ → WorkerType.responder))
  }

  def scan(analyzerPaths: Seq[(Path, WorkerType.Type)]): Unit = {
    val analyzers = (for {
      (analyzerPath, analyzerType) ← analyzerPaths
      analyzerDir ← Try(Files.newDirectoryStream(analyzerPath).asScala).getOrElse {
        logger.warn(s"Analyzer directory ($analyzerPath) is not found")
        Nil
      }
      if Files.isDirectory(analyzerDir)
      infoFile ← Files.newDirectoryStream(analyzerDir, "*.json").asScala
      analyzerDefinition ← WorkerDefinition.fromPath(infoFile, analyzerType).fold(
        error ⇒ {
          logger.warn("Analyzer definition file read error", error)
          Nil
        },
        ad ⇒ Seq(ad))
    } yield analyzerDefinition.id → analyzerDefinition)
      .toMap

    workerMapLock.synchronized {
      workerMap = analyzers
    }
    logger.info(s"New analyzer list:\n\n\t${workerMap.values.map(a ⇒ s"${a.name} ${a.version}").mkString("\n\t")}\n")
  }

  def create(organization: Organization, workerDefinition: WorkerDefinition, workerFields: Fields)(implicit authContext: AuthContext): Future[Worker] = {
    val rawConfig = workerFields.getValue("configuration").fold(JsObject.empty)(_.as[JsObject])
    val configItems = workerDefinition.configurationItems ++ BaseConfig.global(workerDefinition.tpe).items ++ BaseConfig.tlp.items ++ BaseConfig.pap.items
    val configOrErrors = configItems
      .validatedBy(_.read(rawConfig))
      .map(JsObject.apply)

    val unknownConfigItems = (rawConfig.value.keySet -- configItems.map(_.name))
      .foldLeft[Unit Or Every[AttributeError]](Good(())) {
        case (Good(_), ci) ⇒ Bad(One(UnknownAttributeError("worker.config", JsString(ci))))
        case (Bad(e), ci)  ⇒ Bad(UnknownAttributeError("worker.config", JsString(ci)) +: e)
      }

    withGood(configOrErrors, unknownConfigItems)((c, _) ⇒ c)
      .fold(cfg ⇒ {
        createSrv[WorkerModel, Worker, Organization](workerModel, organization, workerFields
          .set("workerDefinitionId", workerDefinition.id)
          .set("description", workerDefinition.description)
          .set("configuration", cfg.toString)
          .set("type", workerDefinition.tpe.toString)
          .addIfAbsent("dataTypeList", StringInputValue(workerDefinition.dataTypeList)))

      }, {
        case One(e)         ⇒ Future.failed(e)
        case Every(es @ _*) ⇒ Future.failed(AttributeCheckingError(s"analyzer(${workerDefinition.name}).configuration", es))
      })
  }

  def create(organizationId: String, workerDefinition: WorkerDefinition, workerFields: Fields)(implicit authContext: AuthContext): Future[Worker] = {
    for {
      organization ← organizationSrv.get(organizationId)
      analyzer ← create(organization, workerDefinition, workerFields)
    } yield analyzer
  }

  def delete(analyzer: Worker)(implicit authContext: AuthContext): Future[Unit] =
    deleteSrv.realDelete(analyzer)

  def delete(analyzerId: String)(implicit authContext: AuthContext): Future[Unit] =
    deleteSrv.realDelete[WorkerModel, Worker](workerModel, analyzerId)

  def update(analyzer: Worker, fields: Fields)(implicit authContext: AuthContext): Future[Worker] = update(analyzer, fields, ModifyConfig.default)

  def update(analyzer: Worker, fields: Fields, modifyConfig: ModifyConfig)(implicit authContext: AuthContext): Future[Worker] = {
    val analyzerFields = fields.getValue("configuration").fold(fields)(cfg ⇒ fields.set("configuration", cfg.toString))
    updateSrv(analyzer, analyzerFields, modifyConfig)
  }

  def update(analyzerId: String, fields: Fields)(implicit authContext: AuthContext): Future[Worker] = update(analyzerId, fields, ModifyConfig.default)

  def update(analyzerId: String, fields: Fields, modifyConfig: ModifyConfig)(implicit authContext: AuthContext): Future[Worker] = {
    get(analyzerId).flatMap(analyzer ⇒ update(analyzer, fields, modifyConfig))
  }
}