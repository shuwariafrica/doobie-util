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
package africa.shuwari.doobie.test

import cats.effect.IO
import doobie.*

object database:

  final val dbServerPassword = sys.env.getOrElse("MSSQL_SA_PASSWORD", "Mandatory@Passw0rd")
  final val dbName = sys.env.getOrElse("MSSQL_TARGET_DATABASE", "doobie-util")

  val defaultTransactor: doobie.Transactor[IO] = Transactor.fromDriverManager[IO](
    driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver",
    url = s"jdbc:sqlserver://localhost:65535;"
      + s"database=$dbName;"
      + "user=sa;"
      + s"password=$dbServerPassword;"
      + "encrypt=true;"
      + "trustServerCertificate=true;"
      + "loginTimeout=30;",
    logHandler = None
  )

end database
