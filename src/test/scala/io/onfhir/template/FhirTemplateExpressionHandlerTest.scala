package io.onfhir.template


import java.time.temporal.Temporal
import java.time.{Duration, ZonedDateTime}
import io.onfhir.expression.{FhirExpression, FhirExpressionException}

import scala.io.Source
import io.onfhir.path.FhirPathEvaluator
import io.onfhir.util.JsonFormatter._
import org.json4s.JsonAST.{JArray, JDouble, JNothing, JObject, JString}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should

class FhirTemplateExpressionHandlerTest
    extends AsyncFlatSpec
    with should.Matchers
    with OptionValues {
  implicit val formats = io.onfhir.util.JsonFormatter.formats
  //template 1
  val template1 = Source.fromInputStream(getClass.getResourceAsStream("/templates/template-expression1.json")).mkString.parseJson.extract[FhirExpression]
  val event1Content = Source.fromInputStream(getClass.getResourceAsStream("/resources/observation-event1.json")).mkString.parseJson
  val context1CareTeam = Source.fromInputStream(getClass.getResourceAsStream("/resources/careteam-context-param1.json")).mkString.parseJson

  //Template 2
  val template2 = Source.fromInputStream(getClass.getResourceAsStream("/templates/template-expression2.json")).mkString.parseJson.extract[FhirExpression]
  //Template 3
  val template3 = Source.fromInputStream(getClass.getResourceAsStream("/templates/template-expression3.json")).mkString.parseJson.extract[FhirExpression]
  //Template 4
  val template4 = Source.fromInputStream(getClass.getResourceAsStream("/templates/template-expression4.json")).mkString.parseJson.extract[FhirExpression]
  val event4Content = Source.fromInputStream(getClass.getResourceAsStream("/resources/communication-event1.json")).mkString.parseJson

  val fhirTemplateExpressionHandler = new FhirTemplateExpressionHandler(isSourceContentFhir = true)

  "FhirTemplateExpressionHandler" should "handle templates with FHIR path expressions" in{
      val resultFuture = fhirTemplateExpressionHandler.evaluateExpression(template1, Map("careTeamOfPatient" -> context1CareTeam), event1Content)
    resultFuture
      .map(result => {
        FhirPathEvaluator().evaluateString("instantiatesUri", result) shouldBe Seq("http://onfhir.io/PipelineDefinition/sample-pipeline")
        FhirPathEvaluator().evaluateString("subject.reference", result) shouldBe Seq("Patient/f001")
        val sentTime:Temporal = FhirPathEvaluator().evaluateDateTime("sent", result).head
        Duration.between(sentTime, ZonedDateTime.now()).getSeconds < 1L shouldBe true
        FhirPathEvaluator().evaluateString("recipient.reference", result) shouldBe Seq("CareTeam/ct-0001")
        FhirPathEvaluator().evaluateString("payload.contentString", result).head should startWith("Patient P. van de Heuvel has a very high serum potassium value (6.3 mmol/L on 2013-04-02")
      })
    }

    it should "handle templates that has placeholders for a complete JValue" in {
      val contextParams = Map(
        "subjectRef" -> JString("Patient/123"),
        "issued" -> JString("2013-04-03T15:30:10+01:00"),
        "performer" -> JString("Practitioner/456"),
        "obsValue" -> JDouble(7.2),
        "codings" -> JArray(List(
          JObject(
            "system" -> JString("http://loinc.org"),
            "code" -> JString("1010-1")
          ),
          JObject(
            "system" -> JString("http://snomedct.com"),
            "code" -> JString("1234")
          ))
        )
      )
      val resultFuture = fhirTemplateExpressionHandler.evaluateExpression(template2, contextParams, JNothing)
      resultFuture.map(result => {
        FhirPathEvaluator().evaluateString("subject.reference", result) shouldBe Seq("Patient/123")
        FhirPathEvaluator().evaluateString("performer.reference", result) shouldBe Seq("Practitioner/456")
        FhirPathEvaluator().evaluateDateTime("issued", result).head.asInstanceOf[ZonedDateTime].isEqual(ZonedDateTime.parse("2013-04-03T15:30:10+01:00")) shouldBe true
        FhirPathEvaluator().evaluateNumerical("valueQuantity.value", result).map(_.toDouble) shouldBe Seq(7.2)
        FhirPathEvaluator().evaluateNumerical("code.coding.count()", result).map(_.toLong) shouldBe Seq(2L)
      })
    }

    it should "handle templates with optional placeholders if they are not given" in {
      val contextParams = Map(
        "subjectRef" -> JString("Patient/123"),
        "obsValue" -> JDouble(7.2),
        "codings" -> JArray(List(
          JObject(
            "system" -> JString("http://loinc.org"),
            "code" -> JString("1010-1")
          ),
          JObject(
            "system" -> JString("http://snomedct.com"),
            "code" -> JString("1234")
          ))
        )
      )

      fhirTemplateExpressionHandler.evaluateExpression(template2, contextParams, JNothing)
        .map(result => {
          FhirPathEvaluator().evaluate("performer", result) shouldBe  Nil
          FhirPathEvaluator().evaluate("issued", result) shouldBe Nil
        })
    }

    it should "throw exception if a mandatory placeholder cannot be resolved " in {
      val contextParams = Map(
        "obsValue" -> JDouble(7.2),
        "codings" -> JArray(List(
          JObject(
            "system" -> JString("http://loinc.org"),
            "code" -> JString("1010-1")
          ),
          JObject(
            "system" -> JString("http://snomedct.com"),
            "code" -> JString("1234")
          ))
        )
      )
      //subject ref missing
      recoverToSucceededIf[FhirExpressionException] {
        fhirTemplateExpressionHandler.evaluateExpression(template2, contextParams, JNothing)
      }
    }

    it should "handle templates with sections" in {
      fhirTemplateExpressionHandler.evaluateExpression(template3, Map("careTeamOfPatient" -> context1CareTeam), event1Content)
        .map(result => {
          FhirPathEvaluator().evaluateString("recipient.reference", result).toSet  shouldBe Set("Practitioner/pr1", "Patient/example")
          FhirPathEvaluator().evaluateString("recipient.display", result) shouldBe Seq("Dorothy Dietition") //only one has display
          FhirPathEvaluator().evaluateString("recipient.extension.where(url = 'http://www.c3-cloud.eu/fhir/StructureDefinition/recipientStatus').valueCode", result) shouldBe Seq("sent", "sent")
        })
    }

    it should "handle templates with placeholders inside values" in {
      fhirTemplateExpressionHandler.evaluateExpression(template4, Map(), event4Content)
        .map(result =>
          result.asInstanceOf[JObject].obj.isEmpty shouldBe false
        )
    }
}
