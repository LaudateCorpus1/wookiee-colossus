package com.webtrends.harness.component.colossus.mock

import akka.actor.ActorRef
import colossus.core.ServerRef
import colossus.protocols.http.{HttpHeaders, HttpMethod, HttpRequest, HttpRequestHead, HttpResponse, HttpVersion}
import colossus.testkit.HttpServiceSpec
import com.typesafe.config.{Config, ConfigFactory}
import com.webtrends.harness.component.colossus.ColossusManager
import com.webtrends.harness.component.colossus.command.ColossusCommand
import com.webtrends.harness.service.Service
import com.webtrends.harness.service.test.TestHarness

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.{FiniteDuration, _}

trait MockColossusService extends HttpServiceSpec {
  val th: TestHarness = TestHarness(config, wookieeService, Some(Map("wookiee-colossus" -> classOf[ColossusManager])))
  th.harnessReadyCheck(15.seconds.fromNow)
  val colManager: ActorRef = th.getComponent("wookiee-colossus").get

  // Config for component, override if you want to put in your own
  def config: Config = ConfigFactory.parseString("""
                                                    wookiee-colossus {
                                                      service-name = "test-service"

                                                      internal-server {
                                                        port = 9888
                                                        request-metrics = false
                                                      }

                                                      external-server {
                                                        port = 9889
                                                        request-metrics = false
                                                      }

                                                      server {
                                                        max-connections = 10000
                                                        highwater-max-idle-time = 3 seconds
                                                        max-idle-time = 3 seconds
                                                        tcp-backlog-size = 10000
                                                        low-watermark-percentage = 0.5
                                                        high-watermark-percentage = 0.9
                                                        shutdown-timeout = 5 seconds
                                                        slow-start {
                                                          enabled = false
                                                          initial = 10000
                                                          duration = 1 second
                                                        }
                                                        binding-retry {
                                                          type = "NONE"
                                                        }
                                                        delegator-creation-policy {
                                                          wait-time = 5 second
                                                          retry-policy.type = "NONE"
                                                        }

                                                      }

                                                      service.default {
                                                        request-timeout = 10 seconds
                                                        request-buffer-size = 100
                                                        log-errors = true
                                                        max-request-size = 50 MB
                                                        errors : {
                                                          do-not-log : []
                                                          log-only-name : ["DroppedReplyException"]
                                                        }
                                                      }
                                                    }
    """)

  // List of command names and their associated command classes
  def commands: List[(String, Class[_ <: ColossusCommand], List[Any])]

  // Service to start up, or None if we don't want to start one
  def wookieeService: Option[Map[String, Class[_ <: Service]]]

  // Automatically exclude commands with the same name as others in the suite from being recreated
  def noDuplicates: Boolean = true

  // Override this is trying to hit internal endpoints
  override def service: ServerRef = ColossusManager.getExternalServer

  // Command timeout
  override def requestTimeout: FiniteDuration = FiniteDuration(10000, "ms")

  // Use this method to get a response from any command you've registered, main work horse of class
  def returnResponse(request: HttpRequest): HttpResponse = {
    Await.result(client().send(request), requestTimeout)
  }

  // Convenience method to get an HttpRequestHead for requests
  def getHead(url: String, method: HttpMethod = HttpMethod.Get, headers: HttpHeaders = HttpHeaders.Empty): HttpRequestHead = {
    HttpRequestHead(method, url, HttpVersion.`1.1`, headers)
  }

  commands.foreach { c =>
    MockColossusService.createdCommands synchronized {
      if (!noDuplicates || !MockColossusService.createdCommands.contains(c._1)) {
        MockColossusService.createdCommands += c._1
        if (c._3.nonEmpty) colManager ! c else colManager ! (c._1, c._2)
      }
    }
  }
}

object MockColossusService {
  val createdCommands: mutable.HashSet[String] = mutable.HashSet[String]()
}
