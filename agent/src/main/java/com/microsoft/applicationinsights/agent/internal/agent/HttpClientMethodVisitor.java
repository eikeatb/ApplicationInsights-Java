/*
 * AppInsights-Java
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the ""Software""), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.microsoft.applicationinsights.agent.internal.agent;

import com.microsoft.applicationinsights.agent.internal.coresync.impl.ImplementationsCoordinator;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Created by gupele on 7/27/2015.
 */
public final class HttpClientMethodVisitor extends AbstractHttpMethodVisitor {

    private final static String FINISH_DETECT_METHOD_NAME = "httpMethodFinished";
    private final static String FINISH_METHOD_RETURN_SIGNATURE = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IJ)V";
    private final boolean isW3CEnabled;
    private final boolean isW3CBackportEnabled;

    public HttpClientMethodVisitor(int access,
                                   String desc,
                                   String owner,
                                   String methodName,
                                   MethodVisitor methodVisitor,
                                   ClassToMethodTransformationData additionalData,
                                   boolean isW3CEnabled,
                                   boolean isW3CBackportEnabled) {
        super(access, desc, owner, methodName, methodVisitor, additionalData);
        this.isW3CEnabled = isW3CEnabled;
        this.isW3CBackportEnabled = isW3CBackportEnabled;
    }

    private int deltaInNS;
    private int methodLocal;
    private int uriLocal;
    private int childIdLocal;
    private int correlationContextLocal;
    private int appCorrelationId;
    private int tracestate;
    private int traceparent;

    @Override
    public void onMethodEnter() {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
        deltaInNS = this.newLocal(Type.LONG_TYPE);
        mv.visitVarInsn(LSTORE, deltaInNS);

        // This byte code instrumentation is responsible for injecting legacy AI correlation headers which include
        // Request-Id and Request-Context and Correlation-Context. By default this headers are propagated if W3C
        // is turned off. Please refer to generateChildDependencyId(), retrieveCorrelationContext(),
        // retrieveApplicationCorrelationId() from TelemetryCorrelationUtils class for details on how these headers
        // are created.
        if (!isW3CEnabled) {
            // generate child ID
            mv.visitMethodInsn(INVOKESTATIC, "com/microsoft/applicationinsights/web/internal/correlation/TelemetryCorrelationUtils", "generateChildDependencyId", "()Ljava/lang/String;", false);
            childIdLocal = this.newLocal(Type.getType(Object.class));
            mv.visitVarInsn(ASTORE, childIdLocal);

            // retrieve correlation context
            mv.visitMethodInsn(INVOKESTATIC, "com/microsoft/applicationinsights/web/internal/correlation/TelemetryCorrelationUtils", "retrieveCorrelationContext", "()Ljava/lang/String;", false);
            correlationContextLocal = this.newLocal(Type.getType(Object.class));
            mv.visitVarInsn(ASTORE, correlationContextLocal);

            // retrieve request context
            mv.visitMethodInsn(INVOKESTATIC, "com/microsoft/applicationinsights/web/internal/correlation/TelemetryCorrelationUtils", "retrieveApplicationCorrelationId", "()Ljava/lang/String;", false);
            appCorrelationId = this.newLocal(Type.getType(Object.class));
            mv.visitVarInsn(ASTORE, appCorrelationId);

            // inject headers
            // 2 because the 1 is the method being instrumented
            mv.visitVarInsn(ALOAD, 2);
            mv.visitLdcInsn("Request-Id");
            mv.visitVarInsn(ALOAD, childIdLocal);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/HttpRequest", "addHeader", "(Ljava/lang/String;Ljava/lang/String;)V", true);

            mv.visitVarInsn(ALOAD, 2);
            mv.visitLdcInsn("Correlation-Context");
            mv.visitVarInsn(ALOAD, correlationContextLocal);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/HttpRequest", "addHeader", "(Ljava/lang/String;Ljava/lang/String;)V", true);

            mv.visitVarInsn(ALOAD, 2);
            mv.visitLdcInsn("Request-Context");
            mv.visitVarInsn(ALOAD, appCorrelationId);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/HttpRequest", "addHeader", "(Ljava/lang/String;Ljava/lang/String;)V", true);
        } else {
            // If W3C is enabled, we propagate Traceparent, Tracecontext headers
            // to enable correlation. Please refer to generateChildDependencyTraceparent(), generateChildDependencyTraceparent()
            // from TraceContextCorrelation class on how to generate this headers.

            // generate child Traceparent
            mv.visitMethodInsn(INVOKESTATIC, "com/microsoft/applicationinsights/web/internal/correlation/TraceContextCorrelation",
                "generateChildDependencyTraceparent", "()Ljava/lang/String;", false);
            traceparent = this.newLocal(Type.getType(Object.class));
            mv.visitVarInsn(ASTORE, traceparent);

            mv.visitVarInsn(ALOAD, traceparent);
            mv.visitMethodInsn(INVOKESTATIC, "com/microsoft/applicationinsights/web/internal/correlation/TraceContextCorrelation",
                "createChildIdFromTraceparentString", "(Ljava/lang/String;)Ljava/lang/String;", false);
            childIdLocal = this.newLocal(Type.getType(Object.class));
            mv.visitVarInsn(ASTORE, childIdLocal);

            // retrieve tracestate
            mv.visitMethodInsn(INVOKESTATIC, "com/microsoft/applicationinsights/web/internal/correlation/TraceContextCorrelation",
                "retriveTracestate", "()Ljava/lang/String;", false);
            tracestate = this.newLocal(Type.getType(Object.class));
            mv.visitVarInsn(ASTORE, tracestate);

            // inject headers
            // load 2nd variable because the 1st is the method being instrumented
            mv.visitVarInsn(ALOAD, 2);
            mv.visitLdcInsn("traceparent");
            mv.visitVarInsn(ALOAD, traceparent);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/HttpRequest", "addHeader", "(Ljava/lang/String;Ljava/lang/String;)V", true);

            if (isW3CBackportEnabled) {
                mv.visitVarInsn(ALOAD, 2);
                mv.visitLdcInsn("Request-Id");
                mv.visitVarInsn(ALOAD, childIdLocal);
                mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/HttpRequest", "addHeader", "(Ljava/lang/String;Ljava/lang/String;)V", true);
            }

            mv.visitVarInsn(ALOAD, tracestate);
            Label nullLabel = new Label();
            mv.visitJumpInsn(IFNULL, nullLabel);

            mv.visitVarInsn(ALOAD, 2);
            mv.visitLdcInsn("tracestate");
            mv.visitVarInsn(ALOAD, tracestate);

            mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/HttpRequest", "addHeader", "(Ljava/lang/String;Ljava/lang/String;)V", true);

            // skip adding tracestate
            mv.visitLabel(nullLabel);
        }


        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/HttpRequest", "getRequestLine", "()Lorg/apache/http/RequestLine;", true);
		int requestLineLocal = this.newLocal(Type.getType(Object.class));
		mv.visitVarInsn(ASTORE, requestLineLocal);

        //Load RequestLine instance into Local Array. It contains method name and URI as string which is extracted from it
        mv.visitVarInsn(ALOAD, requestLineLocal);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/RequestLine", "getUri", "()Ljava/lang/String;", true);
        uriLocal = this.newLocal(Type.getType(Object.class));
        mv.visitVarInsn(ASTORE, uriLocal);

        //Get Method Name from RequestLine interface object
        mv.visitVarInsn(ALOAD, requestLineLocal);
        mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/RequestLine", "getMethod", "()Ljava/lang/String;", true);
        methodLocal = this.newLocal(Type.getType(Object.class));
        mv.visitVarInsn(ASTORE, methodLocal);

    }

    protected TempVar duplicateTopStackToTempVariable(Type typeOfTopElementInStack) {
        duplicateTop(typeOfTopElementInStack);
        int tempVarIndex = newLocal(typeOfTopElementInStack);
        storeLocal(tempVarIndex, typeOfTopElementInStack);

        return new TempVar(tempVarIndex);
    }

    @Override
    protected void byteCodeForMethodExit(int opcode) {
        String internalName = Type.getInternalName(ImplementationsCoordinator.class);
        switch (translateExitCode(opcode)) {
            case EXIT_WITH_RETURN_VALUE:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
                mv.visitVarInsn(LLOAD, deltaInNS);
                mv.visitInsn(LSUB);
                mv.visitVarInsn(LSTORE, deltaInNS);

                TempVar resultOfMethod = duplicateTopStackToTempVariable(Type.getType(Object.class));
                mv.visitVarInsn(ALOAD, resultOfMethod.tempVarIndex);
                mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/client/methods/CloseableHttpResponse", "getStatusLine", "()Lorg/apache/http/StatusLine;", true);
                int statusLineLocal = this.newLocal(Type.getType(Object.class));
                mv.visitVarInsn(ASTORE, statusLineLocal);

                mv.visitVarInsn(ALOAD, statusLineLocal);
                mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/StatusLine", "getStatusCode", "()I", true);
                int statusCodeLocal = this.newLocal(Type.INT_TYPE);
                mv.visitVarInsn(ISTORE, statusCodeLocal);

                //get Request-Context from response
                mv.visitVarInsn(ALOAD, resultOfMethod.tempVarIndex);
                mv.visitLdcInsn("Request-Context");
                mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/client/methods/CloseableHttpResponse", "getFirstHeader", "(Ljava/lang/String;)Lorg/apache/http/Header;", true);
                int headerLocal = this.newLocal(Type.getType(Object.class));
                mv.visitVarInsn(ASTORE, headerLocal);

                // if header != null, getValue and continue
                mv.visitVarInsn(ALOAD, headerLocal);
                Label nullLabel = new Label();
                mv.visitJumpInsn(IFNULL, nullLabel);

                mv.visitVarInsn(ALOAD, headerLocal);
                mv.visitMethodInsn(INVOKEINTERFACE, "org/apache/http/Header", "getValue", "()Ljava/lang/String;", true);
                int headerValueLocal = this.newLocal(Type.getType(Object.class));
                mv.visitVarInsn(ASTORE, headerValueLocal);

                int targetLocal = this.newLocal(Type.getType(Object.class));
                if (!isW3CEnabled) {
                    //generate target
                    mv.visitVarInsn(ALOAD, headerValueLocal);
                    mv.visitMethodInsn(INVOKESTATIC, "com/microsoft/applicationinsights/web/internal/correlation/TelemetryCorrelationUtils", "generateChildDependencyTarget", "(Ljava/lang/String;)Ljava/lang/String;", false);
                    mv.visitVarInsn(ASTORE, targetLocal);
                } else {
                    //generate target
                    mv.visitVarInsn(ALOAD, headerValueLocal);
                    mv.visitMethodInsn(INVOKESTATIC, "com/microsoft/applicationinsights/web/internal/correlation/TraceContextCorrelation",
                        "generateChildDependencyTarget", "(Ljava/lang/String;)Ljava/lang/String;", false);
                    mv.visitVarInsn(ASTORE, targetLocal);
                }


                mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "INSTANCE", "L" + internalName + ";");

                mv.visitLdcInsn(getMethodName());
                mv.visitVarInsn(ALOAD, methodLocal);
                //Path is populated in CoreAgentNotificationHandler httpMethodFinished() method
                mv.visitVarInsn(ALOAD, childIdLocal);
                mv.visitVarInsn(ALOAD, uriLocal);
                mv.visitVarInsn(ALOAD, targetLocal); //using derived target
                mv.visitVarInsn(ILOAD, statusCodeLocal);
                mv.visitVarInsn(LLOAD, deltaInNS);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalName, FINISH_DETECT_METHOD_NAME, FINISH_METHOD_RETURN_SIGNATURE, false);

                //skip the following instructions
                Label notNullLabel = new Label();
                mv.visitJumpInsn(GOTO, notNullLabel);

                // if header == null, do the following
                mv.visitLabel(nullLabel);
                mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "INSTANCE", "L" + internalName + ";");
                mv.visitLdcInsn(getMethodName());
                mv.visitVarInsn(ALOAD, methodLocal);
                //Path is populated in CoreAgentNotificationHandler httpMethodFinished() method
                mv.visitVarInsn(ALOAD, childIdLocal);
                mv.visitVarInsn(ALOAD, uriLocal);
                mv.visitInsn(ACONST_NULL); //This target would be populated in the CoreAgentNotificationHandler httpMethodFinished() method
                mv.visitVarInsn(ILOAD, statusCodeLocal);
                mv.visitVarInsn(LLOAD, deltaInNS);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalName, FINISH_DETECT_METHOD_NAME, FINISH_METHOD_RETURN_SIGNATURE, false);
                
                mv.visitLabel(notNullLabel);
                return;

            default:
                return;
        }
    }
}
