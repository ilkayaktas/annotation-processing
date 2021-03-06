package processors.factory;

import annotations.BuilderProperty;
import annotations.Factory;
import com.google.auto.service.AutoService;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Created by iaktas on 26.09.2017 at 14:40.
 */

@AutoService(Processor.class)
public class FactoryProcessor extends AbstractProcessor {

    private Types typeUtils;
    private Elements elementUtils;
    private Filer filer;
    private Messager messager;
    private Map<String, FactoryGroupedClasses> factoryClassMap = new LinkedHashMap<String, FactoryGroupedClasses>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> annotataions = new LinkedHashSet<String>();
        annotataions.add(Factory.class.getCanonicalName());
        annotataions.add(BuilderProperty.class.getCanonicalName());
        return annotataions;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        annotations.forEach(o -> log("supported annotations by FactoryProcessor: "+o.getSimpleName().toString()));
        roundEnv.getElementsAnnotatedWith(Factory.class).forEach(o -> log("annotated elements of Factory annotation: "+o.toString()));

        // Itearate over all @annotations.Factory annotated elements
        for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(Factory.class)) {

            // Check if a class has been annotated with @annotations.Factory
            if (annotatedElement.getKind() != ElementKind.CLASS) {
                error(annotatedElement, "Only classes can be annotated with @%s",
                        Factory.class.getSimpleName());
                return true; // Exit processing
            }


            // We can cast it, because we know that it of ElementKind.CLASS
            TypeElement typeElement = (TypeElement) annotatedElement;

            try {
                FactoryAnnotatedClass annotatedClass =
                        new FactoryAnnotatedClass(typeElement, messager); // throws IllegalArgumentException

                if (!isValidClass(annotatedClass)) {
                    return true; // Error message printed, exit processing
                }

                // Everything is fine, so try to add
                FactoryGroupedClasses factoryClass =
                        factoryClassMap.get(annotatedClass.getQualifiedFactoryGroupName());

                if (factoryClass == null) {
                    String qualifiedGroupName = annotatedClass.getQualifiedFactoryGroupName();
                    factoryClass = new FactoryGroupedClasses(qualifiedGroupName, messager);
                    factoryClassMap.put(qualifiedGroupName, factoryClass);

                    log(qualifiedGroupName + " class group is created and added into map.");
                }

                // Throws IdAlreadyUsedException if id is conflicting with
                // another @annotations.Factory annotated class with the same id
                factoryClass.add(annotatedClass);
            } catch (IllegalArgumentException e) {
                // @annotations.Factory.id() is empty
                error(typeElement, e.getMessage());
                return true;
            } catch (ProcessingException e) {
                error(e.getElement(), e.getMessage());
                return true;
            }



        }

        // We have collected all classes annotated with @annotations.Factory stored as processors.factory.FactoryAnnotatedClass
        // and grouped into processors.factory.FactoryGroupedClasses. Now we are going to generate java files for each annotations.Factory.
        try {
            for (FactoryGroupedClasses factoryClass : factoryClassMap.values()) {
                factoryClass.generateCode(elementUtils, filer);
            }

            factoryClassMap.clear();

        } catch (IOException e) {
            error(null, e.getMessage());
        }
        return false;
    }

    private void error(Element e, String msg, Object... args) {
        messager.printMessage(
                Diagnostic.Kind.ERROR,
                String.format(msg, args),
                e);
    }

    private void log(String message){
        messager.printMessage(
                Diagnostic.Kind.WARNING,message);
    }

    private boolean isValidClass(FactoryAnnotatedClass item) {

        // Cast to TypeElement, has more type specific methods
        TypeElement classElement = item.getTypeElement();

        if (!classElement.getModifiers().contains(Modifier.PUBLIC)) {
            error(classElement, "The class %s is not public.",
                    classElement.getQualifiedName().toString());
            return false;
        }

        // Check if it's an abstract class
        if (classElement.getModifiers().contains(Modifier.ABSTRACT)) {
            error(classElement, "The class %s is abstract. You can't annotate abstract classes with @%",
                    classElement.getQualifiedName().toString(), Factory.class.getSimpleName());
            return false;
        }

        // Check inheritance: Class must be childclass as specified in @annotations.Factory.type();
        TypeElement superClassElement =
                elementUtils.getTypeElement(item.getQualifiedFactoryGroupName());
        if (superClassElement.getKind() == ElementKind.INTERFACE) {
            // Check interface implemented
            if (!classElement.getInterfaces().contains(superClassElement.asType())) {
                error(classElement, "The class %s annotated with @%s must implement the interface %s",
                        classElement.getQualifiedName().toString(), Factory.class.getSimpleName(),
                        item.getQualifiedFactoryGroupName());
                return false;
            }
        } else {
            // Check subclassing
            TypeElement currentClass = classElement;
            while (true) {
                TypeMirror superClassType = currentClass.getSuperclass();

                if (superClassType.getKind() == TypeKind.NONE) {
                    // Basis class (java.lang.Object) reached, so exit
                    error(classElement, "The class %s annotated with @%s must inherit from %s",
                            classElement.getQualifiedName().toString(), Factory.class.getSimpleName(),
                            item.getQualifiedFactoryGroupName());
                    return false;
                }

                if (superClassType.toString().equals(item.getQualifiedFactoryGroupName())) {
                    // Required super class found
                    break;
                }

                // Moving up in inheritance tree
                currentClass = (TypeElement) typeUtils.asElement(superClassType);
            }
        }

        // Check if an empty public constructor is given
        for (Element enclosed : classElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR) {
                ExecutableElement constructorElement = (ExecutableElement) enclosed;
                if (constructorElement.getParameters().size() == 0 && constructorElement.getModifiers()
                        .contains(Modifier.PUBLIC)) {
                    // Found an empty constructor
                    return true;
                }
            }
        }

        // No empty constructor found
        error(classElement, "The class %s must provide an public empty default constructor",
                classElement.getQualifiedName().toString());
        return false;
    }
}