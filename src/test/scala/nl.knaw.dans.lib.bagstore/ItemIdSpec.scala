/**
 * Copyright (C) 2018 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.lib.bagstore

import java.nio.file.Paths
import java.util.UUID

class ItemIdSpec extends ReadOnlyTestSupportFixture {

  "BagId.toString" should "return the same as wrapped UUID.toString" in {
    val uuid = UUID.randomUUID()
    BagId(uuid).toString shouldBe uuid.toString
  }

  "FileId.toString" should "return BagId/path" in {
    val uuid = UUID.randomUUID()
    FileId(uuid, Paths.get("some/path")).toString shouldBe s"$uuid/some/path"
  }

  it should "not accept an empty path" in {
    an[IllegalArgumentException] should be thrownBy FileId(UUID.randomUUID(), Paths.get(""))
  }

  it should "not accept an absolute path" in {
    an[IllegalArgumentException] should be thrownBy FileId(UUID.randomUUID(), Paths.get("/absolute"))
  }
}