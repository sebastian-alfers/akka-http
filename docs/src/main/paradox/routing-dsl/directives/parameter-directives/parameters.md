# parameters

This page explains how to extract multiple *query* parameter values from the request, or parameters that might or might 
not be present.

@@@ div { .group-scala }
## Signature

```scala
def parameters(param: <ParamDef[T]>): Directive1[T]
def parameters(params: <ParamDef[T_i]>*): Directive[T_0 :: ... T_i ... :: HNil]
def parameters(params: <ParamDef[T_0]> :: ... <ParamDef[T_i]> ... :: HNil): Directive[T_0 :: ... T_i ... :: HNil]
```

The signature shown is simplified and written in pseudo-syntax, the real signature uses magnets. <a id="^1" href="#1">[1]</a> The type
`<ParamDef>` doesn't really exist but consists of the syntactic variants as shown in the description and the examples.

> <a id="1" href="#^1">[1]</a> See [The Magnet Pattern](https://spray.readthedocs.io/en/latest/blog/2012-12-13-the-magnet-pattern.html) for an explanation of magnet-based overloading.

@@@

## Description

@@@ div { .group-scala }
The parameters directive filters on the existence of several query parameters and extract their values.

Query parameters can be either extracted as a String or can be converted to another type. The parameter name
is supplied as a String. Parameter extraction can be modified to mark a query parameter
as required, optional, or repeated, or to filter requests where a parameter has a certain value:

`"color"`
: extract the value of parameter "color" as `String`
: reject if the parameter is missing

`"color".optional`
: (symbolic notation `"color".?`)
: extract the optional value of parameter "color" as `Option[String]`

`"color".withDefault("red")`
: (symbolic notation `"color" ? "red"`)
: extract the optional value of parameter "color" as `String` with default value `"red"`

`"color".requiredValue("blue")`
: (symbolic notation `"color" ! "blue"`)
: require the value of parameter "color" to be `"blue"` and extract nothing
: reject if the parameter is missing or has a different value

`"amount".as[Int]`
: extract the value of parameter "amount" as `Int`, you need a matching @apidoc[Unmarshaller] in scope for that to work
(see also @ref[Unmarshalling](../../../common/unmarshalling.md))
: reject if the parameter is missing or can't be unmarshalled to the given type

`"amount".as(unmarshaller)`
: extract the value of parameter "amount" with an explicit @apidoc[Unmarshaller] as described in @ref[Unmarshalling](../../../common/unmarshalling.md)
: reject if the parameter is missing or can't be unmarshalled to the given type

`"distance".repeated`
: extract multiple occurrences of parameter "distance" as `Iterable[String]`

`"distance".as[Int].repeated`
: extract multiple occurrences of parameter "distance" as `Iterable[Int]`, you need a matching @apidoc[Unmarshaller] in scope for that to work
(see also @ref[Unmarshalling](../../../common/unmarshalling.md))

`"distance".as(unmarshaller).repeated`
: extract multiple occurrences of parameter "distance" with an explicit @apidoc[Unmarshaller] as described in @ref[Unmarshalling](../../../common/unmarshalling.md)

You can use @ref[Case Class Extraction](../../case-class-extraction.md) to group several extracted values together into a case-class
instance.

@@@

@@@ div { .group-java }
In order to filter on the existence of several query parameters, you need to nest as many @ref[parameter](parameter.md) directives as desired.

Query parameters can be either extracted as a `String` or can be converted to another type. Different methods must be used
when the desired parameter is required, optional or repeated.

@@@

Requests missing a required parameter @scala[or parameter value ]will be rejected with an appropriate rejection. 

If an unmarshaller throws an exception while extracting the value of a parameter, the request will be rejected with a `MissingQueryParameterRejection`
if the unmarshaller threw an `Unmarshaller.NoContentException` or a @apidoc[MalformedQueryParamRejection] in all other cases.
(see also @ref[Rejections](../../../routing-dsl/rejections.md))

@@@ div { .group-scala }
There's also a singular version, @ref[parameter](parameter.md). Form fields can be handled in a similar way, see @ref[`formFields`](../form-field-directives/index.md).

@@@

See @ref[When to use which parameter directive?](index.md#which-parameter-directive) to understand when to use which directive.

## Examples

### Required parameter

Scala
:  @@snip [ParameterDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #required-1 }

Java
:  @@snip [ParameterDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java) { #parameters }

### Optional parameter

Scala
:   @@snip [ParameterDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #optional }

Java
:  @@snip [ParameterDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java) { #optional }
 
@@@ div { .group-scala }
### Optional parameter with default value

@@snip [ParameterDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #optional-with-default }

@@@

### Parameter with required value

@scala[The `requiredValue` decorator makes the route match only if the parameter contains the specified value.]
@java[The directive `parameterRequiredValue` makes the route match only if the parameter contains the specified value.]

Scala
:   @@snip [ParameterDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #required-value }

Java
:   @@snip [ParameterDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java) { #required-value }

### Deserialized parameter

Scala
:   @@snip [ParameterDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #mapped-value }

Java
:  @@snip [ParameterDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java) { #mapped-value }

@@@ div { .group-scala }

### Repeated parameter

@@snip [ParameterDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #repeated }

### CSV parameter

@@snip [ParameterDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #csv }

### Repeated, deserialized parameter

@@snip [ParameterDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #mapped-repeated }

@@@
