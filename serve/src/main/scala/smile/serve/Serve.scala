/*
 * Copyright (c) 2010-2024 Haifeng Li. All rights reserved.
 *
 * Smile is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Smile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Smile.  If not, see <https://www.gnu.org/licenses/>.
 */
package smile.serve

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import akka.actor.CoordinatedShutdown
import akka.actor.typed.{ActorSystem, Terminated}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile
import smile.serve.chat.{ChatRoutes, Generator}

/** LLM Serving.
  *
  * @author Karl Li
  */
object Serve {
  val conf = ConfigFactory.load()
  val home = System.getProperty("smile.home", ".")
  val assets = home + "/chat"
  val dao = new DAO(DatabaseConfig.forConfig[JdbcProfile](conf.getString("smile.serve.db.config")))

  /**
    * Runs an online prediction HTTP server.
    * @param config the service configuration.
    */
  def apply(config: ServeConfig): ActorSystem[Nothing] = {
    val rootBehavior = Behaviors.setup[Nothing] { context =>
      implicit val system = context.system
      implicit val ec = context.executionContext
      // context cannot be used inside Future.onComplete, which is outside of an actor.
      val log = context.log

      log.info("SmileServe initializing...")
      Await.ready(dao.setup(), Duration.Inf) onComplete {
        case Success(_) => log.info("SmileServe initialization succeed")
        case Failure(ex) => log.error("SmileServe initialization failed", ex)
          System.exit(1)
      }

      implicit val exceptionHandler: ExceptionHandler = ExceptionHandler {
        case ex: IllegalArgumentException =>
          log.error("HTTP exception handler", ex)
          complete(HttpResponse(StatusCodes.BadRequest, entity = exceptionMessage(ex)))
        case ex: SecurityException =>
          log.error("HTTP exception handler", ex)
          complete(HttpResponse(StatusCodes.Unauthorized))
      }

      // asking someone requires a timeout if the timeout hits without response
      // the ask is failed with a TimeoutException
      implicit val timeout = Timeout.create(conf.getDuration("smile.serve.timeout"))
      val generator = context.spawn(Generator(config, dao), "Generator")
      context.watch(generator)
      val chat = new ChatRoutes(generator, dao)

      val routes = Route.seal {
        // Route.seal internally wraps its argument route with the handleExceptions
        // directive in order to catch and handle any exception.
        pathPrefix("v1") {
          chat.routes
        } ~
        pathPrefix("chat") {
          get {
            pathEndOrSingleSlash {
              getFromFile(assets + "/index.html")
            } ~
            getFromDirectory(assets)
          }
        }
      }

      startHttpServer(routes)(context.system)
      Behaviors.receiveSignal[Nothing] {
        case (context, Terminated(ref)) =>
          context.log.info("Actor {} stopped. Akka system is terminating...", ref.path.name)
          system.terminate()
          Behaviors.stopped
      }
    }

    val system = ActorSystem[Nothing](rootBehavior, "SmileServe")
    CoordinatedShutdown(system).addJvmShutdownHook {
      dao.db.close()
    }
    system
  }

  /**
    * Returns exception message. In case getMessage returns null
    * (e.g. NullPointerException), the class name is returned.
    */
  private def exceptionMessage(ex: Throwable): String = {
    Option(ex.getMessage()).getOrElse(s"${ex.getClass.getName}(null)")
  }

  private def startHttpServer(routes: Route)(implicit system: ActorSystem[?]): Unit = {
    import system.executionContext

    val interface = conf.getString("akka.http.server.interface")
    val port = conf.getInt("akka.http.server.port")
    val futureBinding = Http().newServerAt(interface, port).bind(routes)
    futureBinding.onComplete {
      case Success(_) =>
        system.log.info("SmileServe service online at http://{}:{}/", interface, port)
      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }
}
