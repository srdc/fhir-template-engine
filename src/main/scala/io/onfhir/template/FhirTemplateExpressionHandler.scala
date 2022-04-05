package io.onfhir.template

import io.onfhir.expression.{FhirExpression, FhirExpressionException, IFhirExpressionLanguageHandler}
import io.onfhir.path.{FhirPathBoolean, FhirPathComplex, FhirPathDateTime, FhirPathEvaluator, FhirPathNumber, FhirPathQuantity, FhirPathString, FhirPathTime, IFhirPathFunctionLibraryFactory}
import org.json4s.{JArray, JNothing, JNull, JObject, JString, JValue}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex
import io.onfhir.util.JsonFormatter._

/**
 * Expression handler for FHIR template language (mustache like) that we devise within onFhir to create dynamic FHIR contents based on placeholder FHIR Path expressions within the template
 * @param staticContextParams       Context params that will be supplied to every evaluation with this instance
 * @param functionLibraryFactories  Function libraries for FHIR Path expression evaluation
 */
class FhirTemplateExpressionHandler(
                                     staticContextParams: Map[String, JValue] = Map.empty,
                                     functionLibraryFactories:Map[String, IFhirPathFunctionLibraryFactory] = Map.empty)  extends IFhirExpressionLanguageHandler {
  /**
   * Supported language mime type
   */
  override val languageSupported: String = "application/fhir-template+json"

  /**
   * Regular expression for a placeholder expression that is the whole JSON value
   */
  private val templateFullPlaceholderValuePattern = """^\{\{((\*?)(\??) )?([^"{}]+)\}\}$""".r
  /**
   * Regular expression for a placeholder expression that is within the JSON values
   */
  private val templatePlaceholderInside = """\{\{([^"{}]+)\}\}""".r
  /**
   * Regular expression for Template Section Field
   */
  private val templateSectionField = """^\{\{#(\w+)\}\}$""".r
  /**
   * Regular expression for Template Section Value
   */
  private val templateSectionExpressionValue = """^\{\{([^"{}]+)\}\}$""".r

  /**
   * Base FHIR path evaluator
   */
  val fhirPathEvaluator =  FhirPathEvaluator.apply(staticContextParams, functionLibraryFactories)

  /**
   * Validate the expression
   * @param expression  Parsed expression
   */
  def validateExpression(expression: FhirExpression):Unit = {
    if(expression.value.isEmpty || expression.value.get.isInstanceOf[JObject])
      throw FhirExpressionException(s"Missing FHIR template content or invalid template!")
  }

  /**
   * Template expressions can not be used as boolean expressions
   * @param expression
   * @param contextParams
   * @param input
   * @param ex
   * @throws
   * @return
   */
  @throws[FhirExpressionException]
  def satisfies(expression: FhirExpression, contextParams: Map[String, JValue], input:JValue = JNothing)(implicit ex:ExecutionContext): Future[Boolean] = {
    throw FhirExpressionException(s"Do not use FHIR Templates for applicability checks'!")
  }

  /**
   * Evaluate the given template
   * @param expression    Template content
   * @param contextParams Supplied context parameters for the evaluation
   * @param input         Given input content for evaluation
   * @return
   */
  override def evaluateExpression(expression: FhirExpression, contextParams: Map[String, JValue], input: JValue)(implicit ex: ExecutionContext): Future[JValue] = {
    Future.apply {
      val fhirTemplate = expression.value.get
      //Get the final evaluator
      val evaluator = if(contextParams.isEmpty) fhirPathEvaluator else fhirPathEvaluator.copy(environmentVariables = fhirPathEvaluator.environmentVariables ++ contextParams)

      val filledTemplate = evaluateTemplate(fhirTemplate, evaluator, input)

      removeEmptyFields(filledTemplate)
    }
  }

  /**
   * Evaluate the template recursively going on every leaf
   * @param template            Template content
   * @param fhirPathEvaluator   FHIR Path evaluator
   * @param input               Supplied input content for the evaluation
   * @return
   */
  private def evaluateTemplate(template:JValue, fhirPathEvaluator:FhirPathEvaluator, input: JValue):JValue = {
    template match {
      // The value is given by a placeholder
      case JString(s) if s.startsWith("{{") && s.endsWith("}}") =>
        templateFullPlaceholderValuePattern.findFirstMatchIn(s) match {
          case None => handleInternalMatches(s, fhirPathEvaluator, input)
          case Some(m) => handleCompleteValueMatch(m, fhirPathEvaluator, input)
        }

      //There may be some placeholders within the string
      case JString(s) =>
        handleInternalMatches(s, fhirPathEvaluator, input)

      //A section definition within template (like mustache sections) for repetitive or optional sections
      case JObject(List((sectionField,JString(sectionStatement)), (valueField,valuePart))) if sectionField.startsWith("{{#") && sectionField.endsWith("}}")  =>
        handleTemplateSection(sectionField, sectionStatement, valueField, valuePart, fhirPathEvaluator, input)

      //Go recursive on fields
      case JObject(fields) => JObject(fields.map(f => f._1 -> evaluateTemplate(f._2, fhirPathEvaluator, input)))
      case JArray(vs) =>
        JArray(vs.flatMap(
          evaluateTemplate(_, fhirPathEvaluator, input) match {
            case arr:JArray => arr.arr  //If the inner part returns an array merge it with others as generally we don't have array of arrays in FHIR
            case oth => List(oth)
          }
        ))
      //Otherwise the same
      case oth => oth
    }
  }

  /**
   * Handle template section
   * @param sectionField
   * @param sectionStatement
   * @param valueField
   * @param valuePart
   * @param fhirPathEvaluator
   * @param input
   * @return
   */
  private def handleTemplateSection(sectionField:String, sectionStatement:String, valueField:String, valuePart:JValue, fhirPathEvaluator:FhirPathEvaluator,  input: JValue):JValue = {
    //Resolve section variable name e.g. {{#member}} -> member
    val sectionFieldVar = templateSectionField.findFirstMatchIn(sectionField) match {
      case None => throw FhirExpressionException(s"Invalid FHIR template section field '$sectionField'! It should be in format {{#<variable-name>}} e.g. {{#member}}.")
      case Some(m) => m.group(1)
    }
    //Resolve section variable FHIR Path statement e.g. {{CareTeam.participant.member}}
    val sectionPathStatement = templateSectionExpressionValue.findFirstMatchIn(sectionStatement) match {
      case None => throw FhirExpressionException(s"Invalid FHIR template section statement '$sectionStatement'! It should be in format {{<FHIR path statement>}}.")
      case Some(s) => s.group(1)
    }
    //Evaluate the section variable statement
    val sectionResults = fhirPathEvaluator.evaluate(sectionPathStatement, input).map(_.toJson)
    //For each entry for the section variable evaluate the section value part
    val finalResults =
      sectionResults
        .map(sectionResult => {
          evaluateTemplate(valuePart, fhirPathEvaluator.withEnvironmentVariable(sectionFieldVar, sectionResult), input)
        })

    valueField match {
      //section is an array
      case "{{*}}" => if(finalResults.isEmpty) JNull else JArray(finalResults.toList)
      case "{{?}}" => finalResults.headOption.getOrElse(JNull)
      case _ => throw  FhirExpressionException(s"Invalid FHIR template section value field '$valueField'! Use '{{*}}' for arrays and '{{?}}' for optional JSON objects.")
    }
  }

  /**
   * Remove empty arrays, objects or null valued fields from the given JValue
   * @param value
   * @return
   */
  private def removeEmptyFields(value:JValue):JValue = {
    value match {
      case JObject(elems) =>
        elems
          .map(el => el._1 -> removeEmptyFields(el._2))
          .filterNot(_._2 == JNull) match {
          case l if l.isEmpty => JNull
          case oth => JObject(oth)
        }
      case JArray(arr) =>
        arr
          .map(removeEmptyFields)
          .filterNot(_ == JNull) match {
          case l if l.isEmpty => JNull
          case oth => JArray(oth)
        }
      case oth => oth
    }
  }

  /**
   * Handle internal placeholders within String values
   * @param strValue            String value possibly with placeholders within
   * @param fhirPathEvaluator   FHIR Path evaluator
   * @param input               Input for FHIR path evaluations
   * @return
   */
  private def handleInternalMatches(strValue:String, fhirPathEvaluator:FhirPathEvaluator, input:JValue):JString = {
    def findMatch(m:Regex.Match):String = {
      val fhirPathExpression = m.group(1)
      fhirPathEvaluator.evaluate(fhirPathExpression, input) match {
        case Seq(fhirPathResult) => fhirPathResult match {
          case FhirPathComplex(_) | FhirPathQuantity(_, _) =>
            throw FhirExpressionException(s"FHIR path expression '$fhirPathExpression' returns complex JSON object although it is used within a FHIR string value!")
          case FhirPathString(s) => s
          case FhirPathNumber(n) => "" + n.toDouble.toString
          case FhirPathBoolean(b) => "" + b
          case dt:FhirPathDateTime =>
            dt.toJson.toJson.dropRight(1).drop(1)
          case t:FhirPathTime => t.toJson.toJson.dropRight(1).drop(1)
        }
        case _ =>
          throw FhirExpressionException(s"FHIR path expression '$fhirPathExpression' returns multiple or empty result although it is used within a FHIR string value!")
      }
    }

    //Fill the placeholder values
    val filledValue = templatePlaceholderInside.replaceAllIn(strValue, replacer = findMatch)
    JString(filledValue)
  }

  /**
   * Handle placeholders that represents the Json value completely e.g. "value" : "{{Observation.valueQuantity.value}}"
   * @param m                   Matched regular expression group
   * @param fhirPathEvaluator   FHIR Path evaluator
   * @param input               Input for FHIR path evaluations
   * @return
   */
  private def handleCompleteValueMatch(m:Regex.Match, fhirPathEvaluator:FhirPathEvaluator, input:JValue):JValue = {
    val isArray = m.group(2) == "*"
    val isOptional = m.group(3) == "?"
    val fhirPathExpression = m.group(4)

    val result =
      fhirPathEvaluator
        .evaluate(fhirPathExpression, input)
        .map(_.toJson)

    if(!isOptional && result.isEmpty)
      throw FhirExpressionException(s"FHIR path expression '$fhirPathExpression' returns empty although value is not marked as optional! Please use '?' mark in placeholder e.g. {{? <fhir-path-expression>}}  or correct your expression")

    if(!isArray && result.length > 1)
      throw FhirExpressionException(s"FHIR path expression '$fhirPathExpression' returns multiple results although value is not marked as array! Please use '*' mark in placeholder e.g. {{* <fhir-path-expression>}} or correct your expression")

    if(isArray)
      JArray(result.toList)
    else
      result.headOption.getOrElse(JNull)
  }
}

