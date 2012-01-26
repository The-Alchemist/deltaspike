/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.deltaspike.core.impl.exclude;

import org.apache.deltaspike.core.api.config.ConfigResolver;
import org.apache.deltaspike.core.api.metadata.builder.AnnotatedTypeBuilder;
import org.apache.deltaspike.core.spi.activation.Deactivatable;
import org.apache.deltaspike.core.api.exclude.Exclude;
import org.apache.deltaspike.core.api.interpreter.ExpressionInterpreter;
import org.apache.deltaspike.core.api.projectstage.ProjectStage;
import org.apache.deltaspike.core.impl.interpreter.PropertyExpressionInterpreter;
import org.apache.deltaspike.core.util.ClassDeactivation;
import org.apache.deltaspike.core.util.ClassUtils;
import org.apache.deltaspike.core.util.ProjectStageProducer;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.Alternative;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.util.Nonbinding;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>This class implements the logic for handling
 * {@link org.apache.deltaspike.core.api.exclude.Exclude} annotations.</p>
 * <p/>
 * <p>Further details see {@link org.apache.deltaspike.core.api.exclude.Exclude}</p>
 */
public class ExcludeExtension implements Extension, Deactivatable
{
    private static final Logger LOG = Logger.getLogger(ExcludeExtension.class.getName());

    private Boolean isActivated = null;
    private Boolean isGlobalAlternativeActivated = null;

    /**
     * triggers initialization in any case
     * @param afterDeploymentValidation observed event
     */
    @SuppressWarnings("UnusedDeclaration")
    protected void initProjectStage(@Observes AfterDeploymentValidation afterDeploymentValidation)
    {
        ProjectStageProducer.getInstance();
    }

    /**
     * Observer which is vetoing beans based on {@link Exclude}
     * @param processAnnotatedType observed event
     */
    @SuppressWarnings("UnusedDeclaration")
    protected void vetoBeans(@Observes ProcessAnnotatedType<Object> processAnnotatedType, BeanManager beanManager)
    {
        initActivation();

        //we need to do it before the exclude logic to keep the @Exclude support for global alternatives
        if (this.isGlobalAlternativeActivated)
        {
            activateGlobalAlternatives(processAnnotatedType, beanManager);
        }

        if (!this.isActivated)
        {
            return;
        }

        //TODO needs further discussions for a different feature CodiStartupBroadcaster.broadcastStartup();

        if (!processAnnotatedType.getAnnotatedType().getJavaClass().isAnnotationPresent(Exclude.class))
        {
            return;
        }

        Exclude exclude = processAnnotatedType.getAnnotatedType().getJavaClass().getAnnotation(Exclude.class);

        if (!evalExcludeWithoutCondition(processAnnotatedType, exclude))
        {
            return; //veto called already
        }

        if (!evalExcludeInProjectStage(processAnnotatedType, exclude))
        {
            return; //veto called already
        }

        if (!evalExcludeNotInProjectStage(processAnnotatedType, exclude))
        {
            return; //veto called already
        }

        evalExcludeWithExpression(processAnnotatedType, exclude);
    }

    //just #veto the original implementation and remove @Alternative from the ProcessAnnotatedType of
    // the configured alternative doesn't work with OWB (due to OWB-643)
    private void activateGlobalAlternatives(ProcessAnnotatedType<Object> processAnnotatedType, BeanManager beanManager)
    {
        //the current bean is the bean with a potential global alternative
        Class<Object> currentBean = processAnnotatedType.getAnnotatedType().getJavaClass();

        Set<Class> beanBaseTypes = resolveBeanTypes(currentBean);

        boolean isAlternativeBeanImplementation = currentBean.isAnnotationPresent(Alternative.class);

        List<Annotation> qualifiersOfCurrentBean =
            resolveQualifiers(processAnnotatedType.getAnnotatedType().getAnnotations(), beanManager);

        String configuredBeanName;
        List<Annotation> qualifiersOfConfiguredBean;
        Class<Object> alternativeBeanClass;
        Set<Annotation> alternativeBeanAnnotations;
        
        for (Class currentType : beanBaseTypes)
        {
            alternativeBeanAnnotations = new HashSet<Annotation>();

            configuredBeanName = ConfigResolver.getPropertyValue(currentType.getName());
            if (configuredBeanName != null && configuredBeanName.length() > 0)
            {
                alternativeBeanClass = ClassUtils.tryToLoadClassForName(configuredBeanName);

                if (alternativeBeanClass == null)
                {
                    throw new IllegalStateException("Can't find class " + configuredBeanName + " which is configured" +
                            " for " + currentType.getName());
                }
                alternativeBeanAnnotations.addAll(Arrays.asList(alternativeBeanClass.getAnnotations()));
                qualifiersOfConfiguredBean = resolveQualifiers(alternativeBeanAnnotations, beanManager);
            }
            else
            {
                continue;
            }

            if (isAlternativeBeanImplementation)
            {
                if (doQualifiersMatch(qualifiersOfCurrentBean, qualifiersOfConfiguredBean))
                {
                    //veto if the current annotated-type is a global alternative - it replaced the original type already
                    processAnnotatedType.veto();
                    break;
                }
            }
            else
            {
                if (!alternativeBeanClass.isAnnotationPresent(Alternative.class))
                {
                    continue;
                }

                if (doQualifiersMatch(qualifiersOfCurrentBean, qualifiersOfConfiguredBean))
                {
                    AnnotatedTypeBuilder<Object> annotatedTypeBuilder
                        = new AnnotatedTypeBuilder<Object>().readFromType(alternativeBeanClass);

                    //just to avoid issues with switching between app-servers,...
                    annotatedTypeBuilder.removeFromClass(Alternative.class);
                    processAnnotatedType.setAnnotatedType(annotatedTypeBuilder.create());
                }
            }
        }
    }

    private boolean doQualifiersMatch(List<Annotation> qualifiersOfCurrentBean,
                                      List<Annotation> qualifiersOfConfiguredBean)
    {
        if (qualifiersOfCurrentBean.size() != qualifiersOfConfiguredBean.size())
        {
            return false;
        }

        int matchingQualifiers = 0;
        for (Annotation currentQualifier : qualifiersOfCurrentBean)
        {
            for (Annotation qualifierConfiguredBean : qualifiersOfConfiguredBean)
            {
                if (doesQualifierMatch(currentQualifier, qualifierConfiguredBean))
                {
                    matchingQualifiers++;
                    break;
                }
            }
        }
        return qualifiersOfConfiguredBean.size() == matchingQualifiers;
    }

    private boolean doesQualifierMatch(Annotation currentQualifier, Annotation qualifierConfiguredBean)
    {
        if (!currentQualifier.annotationType().equals(qualifierConfiguredBean.annotationType()))
        {
            return false;
        }

        Object currentValue;
        Object valueOfQualifierConfiguredBean;
        for (Method currentMethod : currentQualifier.annotationType().getDeclaredMethods())
        {
            if (currentMethod.isAnnotationPresent(Nonbinding.class))
            {
                continue;
            }

            try
            {
                currentMethod.setAccessible(true);
                currentValue = currentMethod.invoke(currentQualifier);
                valueOfQualifierConfiguredBean = currentMethod.invoke(qualifierConfiguredBean);

                if (!currentValue.equals(valueOfQualifierConfiguredBean))
                {
                    return false;
                }
            }
            catch (Exception e)
            {
                throw new IllegalStateException("Can't compare " + currentQualifier.annotationType().getName() +
                    " with " + qualifierConfiguredBean.annotationType().getName());
            }
        }
        return true;
    }

    private List<Annotation> resolveQualifiers(Set<Annotation> annotations, BeanManager beanManager)
    {
        List<Annotation> result = new ArrayList<Annotation>();

        for (Annotation annotation : annotations)
        {
            if (beanManager.isQualifier(annotation.annotationType()))
            {
                result.add(annotation);
            }
        }
        return result;
    }

    private Set<Class> resolveBeanTypes(Class beanClass)
    {
        Set<Class> result = new HashSet<Class>();

        Class<?> currentClass = beanClass;
        while (currentClass != null && !Object.class.getName().equals(currentClass.getName()))
        {
            result.add(currentClass);

            for (Class interfaceClass : currentClass.getInterfaces())
            {
                if (interfaceClass.getName().startsWith("java.") || interfaceClass.getName().startsWith("javax."))
                {
                    continue;
                }
                result.addAll(resolveBeanTypes(interfaceClass));
            }

            currentClass = currentClass.getSuperclass();
        }

        return result;
    }

    private boolean evalExcludeWithoutCondition(ProcessAnnotatedType<Object> processAnnotatedType, Exclude exclude)
    {
        if (exclude.ifProjectStage().length == 0 && exclude.exceptIfProjectStage().length == 0 &&
                "".equals(exclude.onExpression()))
        {
            veto(processAnnotatedType, "Stateless");
            return false;
        }
        return true;
    }

    private boolean evalExcludeInProjectStage(ProcessAnnotatedType<Object> processAnnotatedType, Exclude exclude)
    {
        Class<? extends ProjectStage>[] activatedIn = exclude.ifProjectStage();

        if (activatedIn.length == 0)
        {
            return true;
        }

        if (isInProjectStage(activatedIn))
        {
            veto(processAnnotatedType, "IfProjectState");
            return false;
        }
        return true;
    }

    private boolean evalExcludeNotInProjectStage(ProcessAnnotatedType<Object> processAnnotatedType, Exclude exclude)
    {
        Class<? extends ProjectStage>[] notIn = exclude.exceptIfProjectStage();

        if (notIn.length == 0)
        {
            return true;
        }

        if (!isInProjectStage(notIn))
        {
            veto(processAnnotatedType, "ExceptIfProjectState");
            return false;
        }
        return true;
    }

    private void evalExcludeWithExpression(ProcessAnnotatedType<Object> processAnnotatedType, Exclude exclude)
    {
        if ("".equals(exclude.onExpression()))
        {
            return;
        }

        if (isDeactivated(exclude, PropertyExpressionInterpreter.class))
        {
            veto(processAnnotatedType, "Expression");
        }
    }

    private boolean isInProjectStage(Class<? extends ProjectStage>[] activatedIn)
    {
        if (activatedIn != null && activatedIn.length > 0)
        {
            ProjectStage ps = ProjectStageProducer.getInstance().getProjectStage();
            for (Class<? extends ProjectStage> activated : activatedIn)
            {
                if (ps.getClass().equals(activated))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isDeactivated(Exclude exclude, Class defaultExpressionInterpreterClass)
    {
        String expressions = exclude.onExpression();

        Class<? extends ExpressionInterpreter> interpreterClass = exclude.interpretedBy();

        if (interpreterClass.equals(ExpressionInterpreter.class))
        {
            interpreterClass = defaultExpressionInterpreterClass;
        }

        ExpressionInterpreter<String, Boolean> expressionInterpreter =
                ClassUtils.tryToInstantiateClass(interpreterClass);

        if (expressionInterpreter == null)
        {
            if (LOG.isLoggable(Level.WARNING))
            {
                LOG.warning("can't instantiate " + interpreterClass.getClass().getName());
            }
            return true;
        }

        return expressionInterpreter.evaluate(expressions);
    }

    private void veto(ProcessAnnotatedType<?> processAnnotatedType, String vetoType)
    {
        processAnnotatedType.veto();
        LOG.finer(vetoType + " based veto for bean with type: " +
                processAnnotatedType.getAnnotatedType().getJavaClass());
    }

    public void initActivation()
    {
        if (isActivated == null)
        {
            isActivated = ClassDeactivation.isActivated(getClass());
            isGlobalAlternativeActivated = ClassDeactivation.isActivated(GlobalAlternative.class);
        }
    }
}
