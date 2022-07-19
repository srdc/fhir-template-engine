# onfhir-template-engine
A FHIR template engine that can create dynamic FHIR content via specific JSON template and FHIR Path expressions inside these templates based on given context. 

## FHIR JSON Template Language  (application/fhir-template+json)
FHIR Json templates are generally used to provide the dynamic FHIR content within a workflow/pipeline e.g. Forming a Communication
resource to create a notification for a practitioner for a specific event or creating a FHIR resource based on some input information. 
Placeholders are given in mustache format and are FHIR path expressions themselves that are evaluated based on event content and 
context variables set for expression evaluation.
You can use the placeholders in 3 different ways in the template.

First one is using the placeholder to provide the complete value of a FHIR element. In this case, the placeholder should
be given as the JSON string (again between {{ }}). However, the FHIR Path expression can return any JSON value even a
JSON object or array which set the value of the field. Further marks are needed for optional and array elements. 

* **'?'**: If the element is optional, and your FHIR Path statement can return empty result, then you should use '?' mark after the
parenthesis and after the empty space provide the FHIR path expression. If '?' is not used, and the expression returns
an empty result then an exception is thrown. 
* **'\*'**: If the FHIR element is an array with cardinality 0-n then you should indicate this with mark '*' after the
parenthesis and then provide the FHIR Path expression after an empty space. '\*' guarantees that even the FHIR path 
expression returns a single item, the result will be always JSON array.
* **'+'**: If the FHIR element is array but its minimum cardinality is one, then use mark '+'. In this way, if the 
following FHIR Path expression returns no result, then an exception is thrown. '+' also guarantees that result will be a 
JSON array.

The following snapshot illustrates this usage. The subject element in FHIR Communication resource is optional,
and assume that we may have Observations (from input) that has no subject. This assignment sets the value of subject 
field to FHIR Reference extracted from the event content (the Observation). If there is no subject then field is not 
included in the result. The placeholder for 'sent' element is another example. This time as we know that our expression 
always return the time during the execution of the template expression we don't use '?' mark. Finally, in the last one 
we also use the '*' mark to indicate that reasonCode is an array element. In this case, even there is only one category 
code in Observation, the result will be an array. But there is no category element, then reasonCode field is not included 
in the result.
```
{
  "resourceType": "Communication",
  ...,
  "subject": "{{? Observation.subject}}",
  "sent": "{{now()}}",
  "reasonCode": "{{* Observation.category}}"
  ...
}
```

Second usage of placeholders is to provide a part of a FHIR string value. The following snapshot shows the usage for
preparing the content of the notification.
```
{
 "resourceType": "Communication",
 ...,
 "payload": [
     {
        "contentString": "Patient {{Observation.subject.display & ''}} has a very high serum potassium value ({{Observation.valueQuantity.value}} mmol/L on {{Observation.effectiveDateTime}})"
     },
     {
        "contentReference": {
            "reference": "Observation/{{Observation.id}}"
        }
     }
  ]
 ...,
}
```
In this usage, the FHIR path expression should return a single and simple JSON value (string, number, boolean, dateTime, time).
If it returns no content, JSON object or array, the engine throws an exception.

Final usage is a bit more complex and used to provide value of a repetitive or optional FHIR elements with looping on 
list of values that you extract from event content or context variables similar to Mustache sections. The following illustrates the usage.
The recipient element is an array element and should indicate the persons to send this communication. For section placeholders;
we use JSON object with two fields, the first provides the list of values to loop on, and the second provides the sub template
that will be executed for each value to create an item in the resulting array. In the name of the first field you should provide the
name of the looping variable in {{#<looping-variable-name>}} format. The value of the field should be again a placeholder
with FHIR expression that extracts the list of values. Here we get the members (FHIR Reference objects) listed in CareTeam
of patient (queried and stored in context variable careTeamOfPatient). The second field should be either {{*}} or {{+}} if element
is repetitive or {{?}} if element is optional. The value of the second field provides the value for each item in a repetitive element
or the value of optional element. Here in our example as you can see, we use 'member' the looping variable in the sub template
as additional context variable (%member). For optional sections,  if the value of looping variable is empty the field is not included.
```
{
    "resourceType": "Communication",
    ...,
    "recipient": {
      "{{#member}}": "{{%careTeamOfPatient.participant.member}}",
      "{{*}}": {
        "reference": "{{%member.reference}}",
        "display": "{{? %member.display}}",
        "extension": [
          {
            "url": "http://www.c3-cloud.eu/fhir/StructureDefinition/recipientStatus",
            "valueCode": "sent"
          }
        ]
      }
    },  
    ...
}
```
