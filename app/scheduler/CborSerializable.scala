package scheduler

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver

@JsonTypeInfo(use = JsonTypeInfo.Id.CUSTOM, include = JsonTypeInfo.As.PROPERTY, property = "_type")
@JsonTypeIdResolver(classOf[TypeIdJacksonResolver])
trait CborSerializable {}
