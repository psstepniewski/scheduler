package scheduler

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase
import com.fasterxml.jackson.databind.{DatabindContext, JavaType}
import play.api.Logging

class TypeIdJacksonResolver extends TypeIdResolverBase with Logging {

  private val resolverId = "1"
  private val resolverVersion = "1"
  private val requiredPrefix = "scheduler."
  private val resolverPrefix = s"@$resolverId.$resolverVersion@"

  override def getMechanism = JsonTypeInfo.Id.CUSTOM

  override def idFromBaseType(): String = throw new UnsupportedOperationException(s"IdTypeResolver[id=$resolverId, version=$resolverVersion] doesn't support `idFromBaseType` operation")

  override def idFromValue(value: Any): String = idFromValueAndType(value, value.getClass)

  override def idFromValueAndType(value: Any, suggestedType: Class[_]): String = {
    val n = value.getClass.getName
    if (n.startsWith(requiredPrefix)) {
      n.replace(requiredPrefix, resolverPrefix)
    }
    else {
      throw new IllegalArgumentException(s"IdTypeResolver[id=$resolverId, version=$resolverVersion]: Value package(${value.getClass.getName}) has to start with requiredPrefix=$requiredPrefix")
    }
  }

  override def typeFromId(context: DatabindContext, id: String): JavaType = {
    if (id.startsWith(resolverPrefix)) {
      val className = id.replace(resolverPrefix, requiredPrefix)
      val clazz = Class.forName(className)
      val javaType = context.constructType(clazz)
      context.constructSpecializedType(javaType, clazz)
    }
    else {
      throw new IllegalArgumentException(s"IdTypeResolver[id=$resolverId, version=$resolverVersion]: Id($id) has to start with resolverPrefix=$resolverPrefix")
    }
  }
}
