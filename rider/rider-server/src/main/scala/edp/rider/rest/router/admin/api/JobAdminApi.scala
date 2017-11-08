package edp.rider.rest.router.admin.api

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Route
import edp.rider.common.{AppResult, RiderLogger}
import edp.rider.rest.persistence.dal.JobDal
import edp.rider.rest.persistence.entities.{FullJobInfo, Job}
import edp.rider.rest.router.{JsonSerializer, ResponseJson, ResponseSeqJson, SessionClass}
import edp.rider.rest.util.AuthorizationProvider
import edp.rider.rest.util.ResponseUtils.getHeader
import edp.rider.spark.{SparkJobClientLog, SparkStatusQuery}
import scala.util.{Failure, Success}

class JobAdminApi(jobDal: JobDal) extends BaseAdminApiImpl(jobDal) with RiderLogger with JsonSerializer {
  override def getByAllRoute(route: String): Route = path(route) {
    get {
      authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
        session =>
          if (session.roleType != "admin") {
            riderLogger.warn(s"${session.userId} has no permission to access it.")
            complete(OK, getHeader(403, session))
          }
          else {
            val jobs = jobDal.getAllJobs
            val uniqueProjectIds = jobs.map(_.projectId).distinct
            val projectIdAndName: Map[Long, String] = jobDal.getAllUniqueProjectIdAndName(uniqueProjectIds)
            if (jobs != null && jobs.nonEmpty) {
              val jobsNameSet = jobs.map(_.name).toSet
              val jobList = jobs.filter(_.startedTime.isDefined)
              val minStartTime = if (jobList.isEmpty) "" else jobList.map(_.startedTime.get).sorted.head //check null to option None todo
              val allAppStatus: List[AppResult] = SparkStatusQuery.getAllAppStatus(minStartTime).filter(t => jobsNameSet.contains(t.appName))
              val jobsGroupByProjectId: Map[Long, Seq[Job]] = jobs.groupBy(_.projectId)
              val rst = jobsGroupByProjectId.flatMap{case (projectId, jobSeq) =>
                SparkStatusQuery.getSparkAllJobStatus(jobSeq, allAppStatus, projectIdAndName(projectId))
              }.toSeq
              riderLogger.info(s"user ${session.userId} select all jobs success.")
              complete(OK, ResponseSeqJson[FullJobInfo](getHeader(200, session), rst))
            }else {
              riderLogger.info(s"user ${session.userId} admin refresh jobs, but no jobs here.")
              complete(OK, ResponseJson[Seq[FullJobInfo]](getHeader(200, session), Seq()))
            }
          }
      }
    }
  }


  def getByProjectIdRoute(route: String): Route = path(route / LongNumber / "jobs") {
    projectId =>
      get {
        authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
          session =>
            if (session.roleType != "admin") {
              riderLogger.warn(s"${session.userId} has no permission to access it.")
              complete(OK, getHeader(403, session))
            }
            else {
              riderLogger.info(s"user ${session.userId} refresh project $projectId")
              val jobs: Seq[Job] = jobDal.getAllJobs4Project(projectId)
              if (jobs != null && jobs.nonEmpty) {
                riderLogger.info(s"user ${session.userId} refresh project $projectId, and job in it is not null and not empty.")
                val projectName = jobDal.adminGetRow(projectId)
                val jobsNameSet = jobs.map(_.name).toSet
                val jobList = jobs.filter(_.startedTime.isDefined)
                val minStartTime = if (jobList.isEmpty) "" else jobList.map(_.startedTime.get).sorted.head //check null to option None todo
                val allAppStatus = SparkStatusQuery.getAllAppStatus(minStartTime).filter(t => jobsNameSet.contains(t.appName))
                val rst: Seq[FullJobInfo] = SparkStatusQuery.getSparkAllJobStatus(jobs, allAppStatus, projectName)
                complete(OK, ResponseJson[Seq[FullJobInfo]](getHeader(200, session), rst.sortBy(_.job.id)))
              } else {
                riderLogger.info(s"user ${session.userId} refresh project $projectId, but no jobs in project.")
                complete(OK, ResponseJson[Seq[FullJobInfo]](getHeader(200, session), Seq()))
              }
            }
        }
      }

  }


  // @Path("/projects/{projectId}/jobs/{jobId}/logs/")
  def getLogByJobId(route: String): Route = path(route / LongNumber / "jobs" / LongNumber / "logs") {
    (projectId, jobId) =>
      get {
        authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
          session =>
            if (session.roleType != "admin") {riderLogger.warn(s"${session.userId} has no permission to access it.")
              complete(OK, getHeader(403, session))
            }
            else {
              if (session.projectIdList.contains(projectId)) {
                onComplete(jobDal.getJobNameByJobID(jobId)) {
                  case Success(job) =>
                    riderLogger.info(s"user ${session.userId} refresh job log where job id is $jobId success.")
                    val log = SparkJobClientLog.getLogByAppName(job.name)
                    complete(OK, ResponseJson[String](getHeader(200, session), log))
                  case Failure(ex) =>
                    riderLogger.error(s"user ${session.userId} refresh job log where job id is $jobId failed", ex)
                    complete(OK, getHeader(451, ex.getMessage, session))
                }
              } else {
                riderLogger.error(s"user ${session.userId} doesn't have permission to access the project $projectId.")
                complete(OK, getHeader(403, session))
              }
            }
        }
      }
  }






}