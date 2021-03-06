/*
 * jabsorb - a Java to JavaScript Advanced Object Request Broker
 * http://www.jabsorb.org
 *
 * Copyright 2007-2008 The jabsorb team
 *
 * based on original code from
 * JSON-RPC-Java - a JSON-RPC to Java Bridge with dynamic invocation
 *
 * Copyright Metaparadigm Pte. Ltd. 2004.
 * Michael Clark <michael@metaparadigm.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.jabsorb.ng.localarg;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.jabsorb.ng.JSONRPCBridge;
import org.jabsorb.ng.localarg.impl.HttpServletRequestArgResolver;
import org.jabsorb.ng.localarg.impl.HttpServletResponseArgResolver;
import org.jabsorb.ng.localarg.impl.HttpSessionArgResolver;
import org.jabsorb.ng.localarg.impl.JSONRPCBridgeServletArgResolver;
import org.jabsorb.ng.logging.ILogger;
import org.jabsorb.ng.logging.LoggerFactory;
import org.jabsorb.ng.reflect.ClassAnalyzer;
import org.jabsorb.ng.serializer.UnmarshallException;

/**
 * Controls registration and lookup of LocalArgResolver classes.
 */
public class LocalArgController {

    /**
     * Key: argClazz (ie Class), Value: HashSet<LocalArgResolverData>
     */
    private static Map<Class<?>, Set<LocalArgResolverData>> localArgResolverMap = new HashMap<Class<?>, Set<LocalArgResolverData>>();

    /**
     * The logger for this class
     */
    private static final ILogger log = LoggerFactory
            .getLogger(LocalArgController.class);

    static {
        // Make sure this doesn't happen until after the variables are assigned!
        LocalArgController.registerLocalArgResolver(HttpServletRequest.class,
                HttpServletRequest.class, new HttpServletRequestArgResolver());
        LocalArgController
                .registerLocalArgResolver(HttpServletResponse.class,
                        HttpServletResponse.class,
                        new HttpServletResponseArgResolver());
        LocalArgController.registerLocalArgResolver(HttpSession.class,
                HttpServletRequest.class, new HttpSessionArgResolver());
        LocalArgController
                .registerLocalArgResolver(JSONRPCBridge.class,
                        HttpServletRequest.class,
                        new JSONRPCBridgeServletArgResolver());
    }

    /**
     * Determine if an argument of the specified class type can be resolved to a
     * local argument that is filled in on the server prior to being invoked.
     * 
     * @param param
     *            local argument class.
     * 
     * @return true if the class can be resolved to a local argument.
     */
    public static boolean isLocalArg(final Class<?> param) {

        synchronized (localArgResolverMap) {
            return localArgResolverMap.containsKey(param);
        }
    }

    /**
     * Registers a Class to be removed from the exported method signatures and
     * instead be resolved locally using context information from the transport.
     * 
     * TODO: make the order that the variables are given to this function the
     * same as the variables are given to LocalArgResolverData
     * 
     * @param argClazz
     *            The class to be resolved locally
     * @param argResolver
     *            The user defined class that resolves the and returns the
     *            method argument using transport context information
     * @param contextInterface
     *            The type of transport Context object the callback is
     *            interested in eg. HttpServletRequest.class for the servlet
     *            transport
     */
    public static void registerLocalArgResolver(final Class<?> argClazz,
            final Class<?> contextInterface, final LocalArgResolver argResolver) {

        synchronized (localArgResolverMap) {
            Set<LocalArgResolverData> resolverSet = localArgResolverMap
                    .get(argClazz);
            if (resolverSet == null) {
                resolverSet = new HashSet<LocalArgResolverData>();
                localArgResolverMap.put(argClazz, resolverSet);
            }
            resolverSet.add(new LocalArgResolverData(argResolver, argClazz,
                    contextInterface));
            ClassAnalyzer.invalidateCache();
        }
        log.info("registerLocalArgResolver",
                "registered local arg resolver "
                        + argResolver.getClass().getName()
                        + " for local class " + argClazz.getName()
                        + " with context " + contextInterface.getName());
    }

    /**
     * Using the caller's context, resolve a given method call parameter to a
     * local argument.
     * 
     * @param context
     *            callers context. In an http servlet environment, this will
     *            contain the servlet request and response objects.
     * @param param
     *            class type parameter to resolve to a local argument.
     * 
     * @return the run time instance that is resolved, to be used when calling
     *         the method.
     * 
     * @throws UnmarshallException
     *             if there if a failure during resolution.
     */
    public static Object resolveLocalArg(final Object context[],
            final Class<?> param) throws UnmarshallException {

        final Set<LocalArgResolverData> resolverSet = localArgResolverMap
                .get(param);
        final Iterator<LocalArgResolverData> i = resolverSet.iterator();
        while (i.hasNext()) {
            final LocalArgResolverData resolverData = i.next();
            for (int j = 0; j < context.length; j++) {
                if (resolverData.understands(context[j])) {
                    try {
                        return resolverData.getArgResolver().resolveArg(
                                context[j]);
                    } catch (final LocalArgResolveException e) {
                        throw new UnmarshallException(
                                "error resolving local argument: " + e, e);
                    }
                }
            }
        }
        throw new UnmarshallException("couldn't find local arg resolver");
    }

    /**
     * Unregisters a LocalArgResolver</b>.
     * 
     * @param argClazz
     *            The previously registered local class
     * @param argResolver
     *            The previously registered LocalArgResolver object
     * @param contextInterface
     *            The previously registered transport Context interface.
     */
    public static void unregisterLocalArgResolver(final Class<?> argClazz,
            final Class<?> contextInterface, final LocalArgResolver argResolver) {

        synchronized (localArgResolverMap) {
            final Set<LocalArgResolverData> resolverSet = localArgResolverMap
                    .get(argClazz);
            if (resolverSet == null
                    || !resolverSet.remove(new LocalArgResolverData(
                            argResolver, argClazz, contextInterface))) {
                log.warning(
                        "unregisterLocalArgResolver",
                        "local arg resolver "
                                + argResolver.getClass().getName()
                                + " not registered for local class "
                                + argClazz.getName() + " with context "
                                + contextInterface.getName());
                return;
            }
            if (resolverSet.isEmpty()) {
                localArgResolverMap.remove(argClazz);
            }
            ClassAnalyzer.invalidateCache();
        }
        log.info("unregisterLocalArgResolver",
                "unregistered local arg resolver "
                        + argResolver.getClass().getName()
                        + " for local class " + argClazz.getName()
                        + " with context " + contextInterface.getName());
    }
}
