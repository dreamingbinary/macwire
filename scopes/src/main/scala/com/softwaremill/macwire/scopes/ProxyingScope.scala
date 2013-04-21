package com.softwaremill.macwire.scopes

import java.util.UUID
import javassist.util.proxy.{ProxyObject, MethodHandler, ProxyFactory}
import java.lang.reflect.Method
import scala.reflect.ClassTag
import sun.misc.Unsafe

trait ProxyingScope extends Scope {
  import ProxyingScope._

  def apply[T](createT: => T)(implicit tag: ClassTag[T]): T = {
    val key = UUID.randomUUID().toString
    val cls = tag.runtimeClass
    val proxyFactory = new ProxyFactory()
    proxyFactory.setSuperclass(cls)
    val proxiedClass = proxyFactory.createClass()
    val methodHandler = new MethodHandler() {
      def invoke(self: Any, thisMethod: Method, proceed: Method, args: Array[AnyRef]) = {
        val instance = get(key, createT)
        thisMethod.invoke(instance, args: _*)
      }
    }

    // The proxy is a subclass, and normally must call some super-constructor, which can have any parameters. Using
    // sun.misc.Unsafe, it is possible to instantiate a class without calling the constructors (see
    // https://community.jboss.org/blogs/stuartdouglas/2010/10/12/weld-cdi-and-proxies). If Unsafe is
    // available and accessible, using this method. Otherwise, we try to invoke the no-arg constructor, and if this is
    // not possible, we invoke any constructor with default values for arguments.
    val instance = UnsafeInstance match {
      case Some(unsafe) => {
        unsafe.allocateInstance(proxiedClass)
      }
      case None => {
        val constructor = findBestConstructor(proxiedClass)
        constructor.newInstance(constructor.getParameterTypes.map(getDefaultValueForClass(_)): _*)
      }
    }

    instance.asInstanceOf[ProxyObject].setHandler(methodHandler)
    instance.asInstanceOf[T]
  }

  private def findBestConstructor(cls: Class[_]) = {
    val ctors = cls.getConstructors
    ctors.find(_.getParameterTypes.size == 0).getOrElse(ctors.head)
  }

  private val TypeDefaults = Map[Class[_], AnyRef](
    java.lang.Byte.TYPE     -> java.lang.Byte.valueOf(0.toByte),
    java.lang.Short.TYPE    -> java.lang.Short.valueOf(0.toShort),
    java.lang.Integer.TYPE  -> java.lang.Integer.valueOf(0),
    java.lang.Float.TYPE    -> java.lang.Float.valueOf(0),
    java.lang.Double.TYPE   -> java.lang.Double.valueOf(0),
    java.lang.Boolean.TYPE  -> java.lang.Boolean.FALSE)

  private def getDefaultValueForClass(cls: Class[_]): AnyRef = TypeDefaults.getOrElse(cls, null)
}

object ProxyingScope {
  val UnsafeInstance: Option[Unsafe] = {
    try {
      val field = classOf[Unsafe].getDeclaredField("theUnsafe")
      field.setAccessible(true)
      Some(field.get(null).asInstanceOf[Unsafe])
    } catch {
      case _: Exception => None
    }
  }
}