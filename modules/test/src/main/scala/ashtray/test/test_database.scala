/*****************************************************************
 * Copyright © Shuwari Africa Ltd. All rights reserved.          *
 *                                                               *
 * Shuwari Africa Ltd. licenses this file to you under the terms *
 * of the Apache License Version 2.0 (the "License"); you may    *
 * not use this file except in compliance with the License. You  *
 * may obtain a copy of the License at:                          *
 *                                                               *
 *     https://www.apache.org/licenses/LICENSE-2.0               *
 *                                                               *
 * Unless required by applicable law or agreed to in writing,    *
 * software distributed under the License is distributed on an   *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,  *
 * either express or implied. See the License for the specific   *
 * language governing permissions and limitations under the      *
 * License.                                                      *
 *****************************************************************/
package ashtray.test

import cats.effect.IO
import com.dimafeng.testcontainers.MSSQLServerContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import munit.FunSuite
import org.testcontainers.utility.DockerImageName

/** Base test suite trait that provides a SQL Server container for database tests.
  *
  * Uses testcontainers-scala with MUnit to automatically start/stop the container before/after all tests.
  *
  * Note: MSSQL JDBC driver prelogin warnings are suppressed via logging.properties loaded by JVM option.
  * See: https://github.com/testcontainers/testcontainers-java/issues/3079
  */
trait MSSQLContainerSuite extends FunSuite with TestContainerForAll:

  // Use the same RHEL-based SQL Server 2025 image as in docker-compose/Dockerfile
  private val dockerImage = DockerImageName
    .parse("mcr.microsoft.com/mssql/rhel/server:2025-latest")
    .asCompatibleSubstituteFor("mcr.microsoft.com/mssql/server")

  override val containerDef: MSSQLServerContainer.Def = MSSQLServerContainer.Def(
    dockerImageName = dockerImage,
    password = "Mandatory@Passw0rd",
    urlParams = Map(
      "encrypt" -> "true",
      "trustServerCertificate" -> "true"
    )
  )

  /** Returns a transactor for the running container. Must only be called within `withContainers` or after container is
    * started.
    */
  def transactor(container: MSSQLServerContainer): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = container.driverClassName,
      url = container.jdbcUrl,
      user = container.username,
      password = container.password,
      logHandler = None
    )
end MSSQLContainerSuite
