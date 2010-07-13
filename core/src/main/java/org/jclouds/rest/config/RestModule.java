/**
 *
 * Copyright (C) 2009 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
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
 * ====================================================================
 */
package org.jclouds.rest.config;

import java.net.URI;
import java.util.concurrent.ConcurrentMap;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.core.UriBuilder;

import org.jboss.resteasy.specimpl.UriBuilderImpl;
import org.jclouds.http.HttpRequest;
import org.jclouds.http.HttpResponse;
import org.jclouds.http.TransformingHttpCommand;
import org.jclouds.http.TransformingHttpCommandExecutorService;
import org.jclouds.http.TransformingHttpCommandImpl;
import org.jclouds.http.functions.config.ParserModule;
import org.jclouds.internal.ClassMethodArgs;
import org.jclouds.rest.AsyncClientFactory;
import org.jclouds.rest.HttpAsyncClient;
import org.jclouds.rest.HttpClient;
import org.jclouds.rest.annotations.Caller;
import org.jclouds.rest.internal.AsyncRestClientProxy;
import org.jclouds.rest.internal.RestAnnotationProcessor;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.MapMaker;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import com.google.inject.util.Types;

/**
 * 
 * @author Adrian Cole
 */
public class RestModule extends AbstractModule {

   @Override
   protected void configure() {
      install(new ParserModule());
      bind(UriBuilder.class).to(UriBuilderImpl.class);
      bind(AsyncRestClientProxy.Factory.class).to(Factory.class).in(
            Scopes.SINGLETON);
      BinderUtils.bindAsyncClient(binder(), HttpAsyncClient.class);
      BinderUtils.bindClient(binder(), HttpClient.class, HttpAsyncClient.class,
            ImmutableMap.<Class<?>, Class<?>> of(HttpClient.class,
                  HttpAsyncClient.class));
   }

   @Provides
   @Singleton
   @Named("async")
   ConcurrentMap<ClassMethodArgs, Object> provideAsyncDelegateMap(
         CreateAsyncClientForCaller createAsyncClientForCaller) {
      return new MapMaker().makeComputingMap(createAsyncClientForCaller);
   }

   static class CreateAsyncClientForCaller implements
         Function<ClassMethodArgs, Object> {
      private final Injector injector;
      private final AsyncRestClientProxy.Factory factory;

      @Caller
      @Inject(optional = true)
      Module callerModule = new CallerScopedBindingModule();

      @Inject
      CreateAsyncClientForCaller(Injector injector,
            AsyncRestClientProxy.Factory factory) {
         this.injector = injector;
         this.factory = factory;
      }

      @SuppressWarnings("unchecked")
      @Override
      public Object apply(final ClassMethodArgs from) {
         Class clazz = from.getAsyncClass();
         TypeLiteral typeLiteral = TypeLiteral.get(clazz);
         Injector injector = this.injector.createChildInjector(callerModule,
               new AbstractModule() {

                  @Override
                  protected void configure() {
                     bind(ClassMethodArgs.class).annotatedWith(Caller.class)
                           .toInstance(from);
                     install(callerModule);
                  }

               });
         RestAnnotationProcessor util = (RestAnnotationProcessor) injector
               .getInstance(Key.get(TypeLiteral.get(Types.newParameterizedType(
                     RestAnnotationProcessor.class, clazz))));
         // not sure why we have to go back and re-inject this...
         injector.injectMembers(util);
         ConcurrentMap<ClassMethodArgs, Object> delegateMap = injector
               .getInstance(Key.get(
                     new TypeLiteral<ConcurrentMap<ClassMethodArgs, Object>>() {
                     }, Names.named("async")));
         AsyncRestClientProxy proxy = new AsyncRestClientProxy(injector,
               factory, util, typeLiteral, delegateMap);
         injector.injectMembers(proxy);
         return AsyncClientFactory.create(clazz, proxy);
      }
   }

   @Singleton
   public static class CallerScopedBindingModule extends AbstractModule {

      @Provides
      @Caller
      URI provideCallerScopedURI(Injector injector, @Caller ClassMethodArgs args) {
         try {
            return RestAnnotationProcessor.getEndpointFor(args.getMethod(),
                  args.getArgs(), injector);
         } catch (IllegalStateException e) {
            return null;
         }
      }

      @Override
      protected void configure() {

      }
   }

   private static class Factory implements AsyncRestClientProxy.Factory {
      @Inject
      private TransformingHttpCommandExecutorService executorService;

      @SuppressWarnings("unchecked")
      public TransformingHttpCommand<?> create(HttpRequest request,
            Function<HttpResponse, ?> transformer) {
         return new TransformingHttpCommandImpl(executorService, request,
               transformer);
      }

   }

}