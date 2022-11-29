/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package runtime.actionContainers

import spray.json._
import spray.json.DefaultJsonProtocol._

trait PythonAdvancedTests {
  this: PythonBasicTests =>

  it should "detect termination at run" in {
    val (out, err) = withActionContainer() { c =>
      val code =
        """
          |import sys
          |def main(args):
          |  sys.exit(1)
        """.stripMargin

      // action loop detects those errors at init time
      val (initCode, _) = c.init(initPayload(code))
      initCode should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))
      runCode should be(400)
      runRes.get.fields.get("error").get.toString() should include("command exited")
    }
    checkStreams(out, err, {
      case (o, e) =>
        o shouldBe empty
        e shouldBe empty
    })
  }

  it should "detect termination at init" in {
    val (out, err) = withActionContainer() { c =>
      val code =
        """
          |import sys
          |sys.exit(1)
          |def main(args):
          |   pass
        """.stripMargin

      // action loop detects those errors at init time
      val (initCode, initRes) = c.init(initPayload(code))
      initCode should be(502)
      initRes.get.fields.get("error").get.toString() should include("Cannot start action")
    }
    checkStreams(out, err, {
      case (o, e) =>
        o shouldBe empty
        e should include("Command exited abruptly during initialization.")
    })
  }

  it should "read an environment variable" in {
    val (out, err) = withActionContainer() { c =>
      val code = """
                   |import os
                   |X = os.getenv('X')
                   |print(X)
                   |def main(args):
                   |   return { "body": "ok" }
                 """.stripMargin

      // action loop detects those errors at init time
      val (initCode, _) = c.init(initPayload(code, "main", Some(Map("X" -> JsString("xyz")))))
      initCode should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))
      runCode should be(200)
      runRes.get.fields.get("body").get shouldBe JsString("ok")
    }
    checkStreams(out, err, {
      case (o, e) =>
        o should include("xyz")
        e shouldBe empty
    })
  }

  Map(
    "prelaunched" -> Map.empty[String, String],
    "non-prelaunched" -> Map("OW_INIT_IN_ACTIONLOOP" -> ""),
  ).foreach { case (name, env) =>
    it should s"support a function with a lambda-like signature and transform HTTP events $name" in {
      val (out, err) = withActionContainer(env) { c =>
        val code =
          """
            |def main(event, context):
            |   return {
            |      "event": event,
            |      "context": {
            |         "remaining_time": context.get_remaining_time_in_millis(),
            |         "activation_id": context.activation_id,
            |         "function_name": context.function_name,
            |         "function_version": context.function_version
            |      }
            |   }
          """.stripMargin

        val (initCode, _) = c.init(initPayload(code))
        initCode should be(200)

        val (runCode, out) = c.run(runPayload(
          JsObject(
            "__ow_headers" -> JsObject("headerKey" -> "headerValue".toJson),
            "__ow_method" -> "get".toJson,
            "__ow_path" -> "/foo/bar".toJson,
            "__ow_query" -> "foo=bar&foo=baz&test=test".toJson
          ), 
          Some(JsObject(
            "deadline" -> "0".toJson,
            "activation_id" -> "testid".toJson,
            "action_name" -> "testfunction".toJson,
            "action_version" -> "0.0.1".toJson
          ))
        ))
        runCode should be(200)

        out shouldBe Some(JsObject(
          "event" -> JsObject(
            "headers" -> JsObject("headerKey" -> "headerValue".toJson),
            "httpMethod" -> "GET".toJson,
            "path" -> "/foo/bar".toJson,
            "multiValueQueryStringParameters" -> JsObject("foo" -> JsArray("bar".toJson, "baz".toJson), "test" -> JsArray("test".toJson)),
            "queryStringParameters" -> JsObject("foo" -> "bar".toJson, "test" -> "test".toJson),
          ),
          "context" -> JsObject(
            "remaining_time" -> 0.toJson, // This being 0 proofs that the function exists and is callable at least.
            "activation_id" -> "testid".toJson,
            "function_name" -> "testfunction".toJson,
            "function_version" -> "0.0.1".toJson
          )))
      }
    }
  }
}
