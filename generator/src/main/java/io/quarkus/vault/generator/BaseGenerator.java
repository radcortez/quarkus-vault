package io.quarkus.vault.generator;

import static io.quarkus.vault.generator.utils.Strings.capitalize;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.lang.model.element.Modifier;

import jakarta.annotation.Nonnull;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.squareup.javapoet.*;

import io.quarkus.vault.generator.errors.OneOfFieldsMissingError;
import io.quarkus.vault.generator.model.AnyPOJO;
import io.quarkus.vault.generator.model.POJO;
import io.quarkus.vault.generator.model.PartialPOJO;
import io.quarkus.vault.generator.utils.TypeNames;

public abstract class BaseGenerator implements Generator {

    private final Map<ClassName, TypeSpec> generatedTypes = new LinkedHashMap<>();

    abstract protected TypeNames getTypeNames();

    @Override
    public Map<ClassName, TypeSpec> getGeneratedTypes() {
        return generatedTypes;
    }

    protected void addGeneratedType(ClassName name, Function<String, TypeSpec> generator) {
        var type = generatedTypes.get(name);
        if (type != null) {
            return;
        }

        var typeSpec = generator.apply(name.simpleName());
        generatedTypes.put(name, typeSpec);
    }

    public ClassName typeNameFor(String... suffixes) {
        return getTypeNames().typeNameFor(suffixes);
    }

    public ClassName className(String packageName, String name) {
        return getTypeNames().className(packageName, name);
    }

    public ClassName className(Class<?> clazz) {
        return getTypeNames().className(clazz);
    }

    public ClassName className(String name) {
        return getTypeNames().className(name);
    }

    public TypeName typeName(String name) {
        return getTypeNames().typeName(name);
    }

    public TypeName typeName(ClassName name, TypeName... parameterTypes) {
        return getTypeNames().typeName(name, parameterTypes);
    }

    public TypeSpec generatePOJO(String name, AnyPOJO pojo, String generationPrefix) {
        return generatePOJO(name, pojo, generationPrefix, spec -> {
        });
    }

    public TypeSpec generatePOJO(String name, AnyPOJO pojo, String generationPrefix, Consumer<TypeSpec.Builder> customizer) {
        var spec = startPOJO(name, customizer);
        var specName = getTypeNames().typeName(spec.build());
        generatePOJO(spec, specName, pojo, generationPrefix);
        return spec.build();
    }

    public void generatePOJO(TypeSpec.Builder spec, TypeName specName, AnyPOJO pojo, String generationPrefix) {
        pojo.extendsName().ifPresent(extendsName -> spec.superclass(typeName(extendsName)));
        pojo.implementNames().ifPresent(implementNames -> {
            for (var implementName : implementNames) {
                spec.addSuperinterface(typeName(implementName));
            }
        });
        pojo.nested().ifPresent(nested -> addNestedPOJOs(spec, specName, nested));
        pojo.properties().ifPresent(properties -> addPOJOProperties(specName, spec, properties, generationPrefix));
        pojo.methods().ifPresent(methods -> addPOJOMethods(spec, methods));
    }

    public TypeSpec.Builder startPOJO(String name, Consumer<TypeSpec.Builder> customizer) {
        var spec = getTypeNames().classSpecBuilder(name)
                .addModifiers(Modifier.PUBLIC);
        customizer.accept(spec);
        return spec;
    }

    public void addNestedPOJOs(TypeSpec.Builder spec, TypeName specName, List<POJO> nested) {
        ClassName specClassName;
        if (specName instanceof ClassName) {
            specClassName = (ClassName) specName;
        } else if (specName instanceof ParameterizedTypeName parameterizedTypeName) {
            specClassName = parameterizedTypeName.rawType;
        } else {
            throw new IllegalArgumentException("Unsupported specName type: " + specName.getClass());
        }

        for (var pojo : nested) {
            var nestedSpec = startPOJO(pojo.name(), s -> s.addModifiers(Modifier.STATIC));
            var nestedSpecName = specClassName.nestedClass(pojo.name());
            generatePOJO(nestedSpec, nestedSpecName, pojo, "");
            spec.addType(nestedSpec.build());
        }
    }

    public void addPOJOProperties(TypeName specName, TypeSpec.Builder spec, List<POJO.Property> properties,
            String generationPrefix) {

        for (var property : properties) {
            spec.addField(generatePOJOField(spec, property, generationPrefix));
        }

        for (var property : properties) {
            spec.addMethod(generatePOJOSetter(specName, property, generationPrefix));
        }
    }

    public FieldSpec generatePOJOField(TypeSpec.Builder objSpec, POJO.Property property, String generationPrefix) {
        TypeName typeName;
        if (property.type().isPresent()) {

            typeName = typeName(property.type().get());

        } else if (property.object().isPresent()) {

            var clsName = typeNameFor(generationPrefix, property.name());

            addGeneratedType(clsName, className -> generatePOJO(className, PartialPOJO.of(property.object().get()), ""));

            typeName = clsName;

        } else {
            throw OneOfFieldsMissingError.of("No type specified for property " + property.name());
        }

        var spec = FieldSpec.builder(typeName, property.name())
                .addModifiers(Modifier.PUBLIC);

        var serializedName = property.getSerializedName();
        if (!Objects.equals(serializedName, property.name())) {

            spec.addAnnotation(AnnotationSpec.builder(className(JsonProperty.class))
                    .addMember("value", "$S", serializedName)
                    .build());
        }

        if (property.annotations().isPresent()) {

            for (var annotation : property.annotations().get()) {

                var annSpec = AnnotationSpec.builder(className(annotation.type()));

                if (annotation.members().isPresent()) {

                    for (var member : annotation.members().get().entrySet()) {

                        var memberName = member.getKey();
                        var memberValue = member.getValue();
                        var memberFormat = memberValue.format();
                        var memberArguments = memberValue.arguments().orElse(List.of()).stream()
                                .map(argument -> {
                                    if (argument.startsWith("<type>")) {
                                        return typeName(argument.substring("<type>".length()));
                                    } else {
                                        return argument;
                                    }
                                });

                        annSpec.addMember(memberName, memberFormat, memberArguments.toArray());
                    }
                }

                spec.addAnnotation(annSpec.build());
            }
        }

        return spec.build();
    }

    public MethodSpec generatePOJOSetter(TypeName specName, POJO.Property property, String generationPrefix) {

        TypeName typeName;
        if (property.type().isPresent()) {

            typeName = typeName(property.type().get());

        } else if (property.object().isPresent()) {

            typeName = typeNameFor(capitalize(generationPrefix) + capitalize(property.name()));

        } else {
            throw OneOfFieldsMissingError.of("No type specified for property " + property.name());
        }

        var parameterSpec = ParameterSpec.builder(typeName, property.name());
        if (!property.isRequired()) {
            parameterSpec.addAnnotation(className(Nonnull.class));
        }
        var spec = MethodSpec.methodBuilder("set" + capitalize(property.name()))
                .addModifiers(Modifier.PUBLIC)
                .addParameter(typeName, property.name())
                .returns(specName)
                .addStatement("this.$L = $L", property.name(), property.name())
                .addStatement("return this");
        return spec.build();
    }

    public void addPOJOMethods(TypeSpec.Builder spec, List<POJO.Method> methods) {
        for (var method : methods) {
            spec.addMethod(generatePOJOMethod(method));
        }
    }

    public MethodSpec generatePOJOMethod(POJO.Method method) {

        var bodyArgs = method.bodyArguments().orElse(List.of()).stream()
                .map(argument -> {
                    if (argument.startsWith("<type>")) {
                        return typeName(argument.substring("<type>".length()));
                    } else {
                        return argument;
                    }
                })
                .toArray();

        var body = CodeBlock.builder()
                .indent()
                .add(method.body(), bodyArgs)
                .unindent()
                .build();

        var spec = MethodSpec.methodBuilder(method.name())
                .addModifiers(Modifier.PUBLIC)
                .returns(typeName(method.returnType()))
                .addCode(body);

        method.typeParameters().ifPresent(typeParameters -> {
            for (var typeParameter : typeParameters) {
                spec.addTypeVariable(getTypeNames().typeVariableName(typeParameter));
            }
        });

        method.parameters().ifPresent(parameters -> {
            for (var parameter : method.parameters().get().entrySet()) {
                spec.addParameter(typeName(parameter.getValue()), parameter.getKey());
            }
        });

        return spec.build();
    }

}
