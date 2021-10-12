/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.compiler.resolve

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotated
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.platform.js.isJs
import org.jetbrains.kotlin.platform.konan.isNative
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.resolve.descriptorUtil.annotationClass
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.descriptorUtil.platform
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyAnnotationDescriptor
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf
import org.jetbrains.kotlin.types.typeUtil.representativeUpperBound
import org.jetbrains.kotlinx.serialization.compiler.resolve.SerialEntityNames.ENUM_SERIALIZER_FACTORY_FUNC_NAME
import org.jetbrains.kotlinx.serialization.compiler.resolve.SerialEntityNames.MARKED_ENUM_SERIALIZER_FACTORY_FUNC_NAME
import org.jetbrains.kotlinx.serialization.compiler.resolve.SerializationAnnotations.inheritableSerialInfoFqName
import org.jetbrains.kotlinx.serialization.compiler.resolve.SerializationAnnotations.serialInfoFqName

internal fun isAllowedToHaveAutoGeneratedSerializerMethods(
    classDescriptor: ClassDescriptor,
    serializableClassDescriptor: ClassDescriptor
): Boolean {
    if (serializableClassDescriptor.isSerializableEnum()) return true
    // don't generate automatically anything for enums or interfaces or other strange things
    if (serializableClassDescriptor.kind != ClassKind.CLASS) return false
    // it is either GeneratedSerializer implementation
    // or user implementation which does not have type parameters (to be able to correctly initialize descriptor)
    return classDescriptor.typeConstructor.supertypes.any(::isGeneratedKSerializer) ||
            (classDescriptor.typeConstructor.supertypes.any(::isKSerializer) && classDescriptor.declaredTypeParameters.isEmpty())
}

internal fun isKSerializer(type: KotlinType?): Boolean =
    type != null && KotlinBuiltIns.isConstructedFromGivenClass(type, SerialEntityNames.KSERIALIZER_NAME_FQ)

internal fun isGeneratedKSerializer(type: KotlinType?): Boolean =
    type != null && KotlinBuiltIns.isConstructedFromGivenClass(type, SerialEntityNames.GENERATED_SERIALIZER_FQ)

internal fun ClassDescriptor.getGeneratedSerializerDescriptor(): ClassDescriptor =
    module.getClassFromInternalSerializationPackage(SerialEntityNames.GENERATED_SERIALIZER_CLASS.identifier)


internal fun ClassDescriptor.createSerializerTypeFor(argument: SimpleType, baseSerializerInterface: FqName): SimpleType {
    val projectionType = Variance.INVARIANT
    val types = listOf(TypeProjectionImpl(projectionType, argument))
    val descriptor = module.findClassAcrossModuleDependencies(ClassId.topLevel(baseSerializerInterface))
        ?: throw IllegalArgumentException("Can't locate $baseSerializerInterface. Is kotlinx-serialization library present in compile classpath?")
    return KotlinTypeFactory.simpleNotNullType(Annotations.EMPTY, descriptor, types)
}

internal fun extractKSerializerArgumentFromImplementation(implementationClass: ClassDescriptor): KotlinType? {
    val supertypes = implementationClass.typeConstructor.supertypes
    val kSerializerSupertype = supertypes.find { isGeneratedKSerializer(it) }
        ?: supertypes.find { isKSerializer(it) }
        ?: return null
    return kSerializerSupertype.arguments.first().type
}

internal val DeclarationDescriptor.serializableWith: KotlinType?
    get() = annotations.serializableWith(module)

internal fun Annotations.serializableWith(module: ModuleDescriptor): KotlinType? =
    this.findAnnotationKotlinTypeValue(SerializationAnnotations.serializableAnnotationFqName, module,"with")

internal val DeclarationDescriptor.serializerForClass: KotlinType?
    get() = annotations.findAnnotationKotlinTypeValue(SerializationAnnotations.serializerAnnotationFqName, module, "forClass")

internal val ClassDescriptor.isSerialInfoAnnotation: Boolean
    get() = annotations.hasAnnotation(serialInfoFqName) || annotations.hasAnnotation(inheritableSerialInfoFqName)

internal val ClassDescriptor.isInheritableSerialInfoAnnotation: Boolean
    get() = annotations.hasAnnotation(inheritableSerialInfoFqName)

internal val Annotations.serialNameValue: String?
    get() = findAnnotationConstantValue(SerializationAnnotations.serialNameAnnotationFqName, "value")

internal val Annotations.serialRequired: Boolean
    get() = hasAnnotation(SerializationAnnotations.requiredAnnotationFqName)

internal val Annotations.serialTransient: Boolean
    get() = hasAnnotation(SerializationAnnotations.serialTransientFqName)

// ----------------------------------------

val KotlinType?.toClassDescriptor: ClassDescriptor?
    @JvmName("toClassDescriptor")
    get() = this?.constructor?.declarationDescriptor?.let { descriptor ->
        when(descriptor) {
            is ClassDescriptor -> descriptor
            is TypeParameterDescriptor -> descriptor.representativeUpperBound.toClassDescriptor
            else -> null
        }
    }

internal val ClassDescriptor.shouldHaveGeneratedMethodsInCompanion: Boolean
    get() = this.isSerializableObject || this.isSerializableEnum() || this.kind == ClassKind.CLASS && hasSerializableAnnotation

internal val ClassDescriptor.isSerializableObject: Boolean
    get() = kind == ClassKind.OBJECT && hasSerializableAnnotation

internal val ClassDescriptor.isInternallySerializableObject: Boolean
    get() = kind == ClassKind.OBJECT && hasSerializableAnnotationWithoutArgs

internal val ClassDescriptor.isInternalSerializable: Boolean //todo normal checking
    get() {
        if (kind != ClassKind.CLASS) return false
        return hasSerializableAnnotationWithoutArgs
    }

internal fun ClassDescriptor.isSerializableEnum(): Boolean = kind == ClassKind.ENUM_CLASS && hasSerializableAnnotation

internal fun ClassDescriptor.isInternallySerializableEnum(): Boolean = kind == ClassKind.ENUM_CLASS && hasSerializableAnnotationWithoutArgs

internal val ClassDescriptor.shouldHaveGeneratedSerializer: Boolean
    get() = (isInternalSerializable && (modality == Modality.FINAL || modality == Modality.OPEN))
            || (isInternallySerializableEnum() && useLegacyEnums())


internal fun ClassDescriptor.useLegacyEnums(): Boolean {
    // TODO determine compilation is IR or not. This common function called from different places including extension resolvers where it is hard to determine the type of compilation
    val legacyBackend = false

    val functions = module.getPackage(SerializationPackages.internalPackageFqName).memberScope.getFunctionNames()
    return legacyBackend ||
            !functions.contains(ENUM_SERIALIZER_FACTORY_FUNC_NAME) || !functions.contains(MARKED_ENUM_SERIALIZER_FACTORY_FUNC_NAME)
}

internal fun ClassDescriptor.enumEntries(): List<ClassDescriptor> {
    check(this.kind == ClassKind.ENUM_CLASS)
    return unsubstitutedMemberScope.getContributedDescriptors().asSequence()
        .filterIsInstance<ClassDescriptor>()
        .filter { it.kind == ClassKind.ENUM_ENTRY }
        .toList()
}

// check enum or its elements has any SerialInfo annotation
internal fun ClassDescriptor.isEnumWithSerialInfoAnnotation(): Boolean {
    if (kind != ClassKind.ENUM_CLASS) return false
    if (annotations.hasAnySerialAnnotation) return true
    return enumEntries().any { (it.annotations.hasAnySerialAnnotation) }
}

internal val Annotations.hasAnySerialAnnotation: Boolean
    get() = serialNameValue != null || any { it.annotationClass?.isSerialInfoAnnotation == true }

internal val ClassDescriptor.hasSerializableAnnotation
    get() = annotations.hasAnnotation(SerializationAnnotations.serializableAnnotationFqName)

internal val ClassDescriptor.hasSerializableAnnotationWithoutArgs: Boolean
    get() {
        if (!hasSerializableAnnotation) return false
        // If provided descriptor is lazy, carefully look at psi in order not to trigger full resolve which may be recursive.
        // Otherwise, this descriptor is deserialized from another module and it is OK to check value right away.
        val psi = findSerializableAnnotationDeclaration() ?: return (serializableWith == null)
        return psi.valueArguments.isEmpty()
    }

internal val ClassDescriptor.hasSerializableAnnotationWithArgs: Boolean
    get() {
        if (!hasSerializableAnnotation) return false
        // If provided descriptor is lazy, carefully look at psi in order not to trigger full resolve which may be recursive.
        // Otherwise, this descriptor is deserialized from another module and it is OK to check value right away.
        val psi = findSerializableAnnotationDeclaration() ?: return (serializableWith != null)
        return psi.valueArguments.isNotEmpty()
    }

internal fun Annotated.findSerializableAnnotationDeclaration(): KtAnnotationEntry? {
    val lazyDesc = annotations.findAnnotation(SerializationAnnotations.serializableAnnotationFqName) as? LazyAnnotationDescriptor
    return lazyDesc?.annotationEntry
}

// For abstract classes marked with @Serializable,
// methods are generated anyway although they shouldn't have
// generated $serializer and use Polymorphic one.
internal fun ClassDescriptor.isAbstractOrSealedSerializableClass(): Boolean =
    isInternalSerializable && (modality == Modality.ABSTRACT || modality == Modality.SEALED)

internal fun ClassDescriptor.polymorphicSerializerIfApplicableAutomatically(): ClassDescriptor? {
    val serializer = when {
        kind == ClassKind.INTERFACE -> SpecialBuiltins.polymorphicSerializer
        isInternalSerializable && modality == Modality.ABSTRACT -> SpecialBuiltins.polymorphicSerializer
        isInternalSerializable && modality == Modality.SEALED -> SpecialBuiltins.sealedSerializer
        else -> null
    }
    return serializer?.let { module.getClassFromSerializationPackage(it) }
}

// serializer that was declared for this type
internal val ClassDescriptor?.classSerializer: ClassDescriptor?
    get() = this?.let {
        // serializer annotation on class?
        serializableWith?.let { return it.toClassDescriptor }
        // companion object serializer?
        if (hasCompanionObjectAsSerializer) return companionObjectDescriptor
        // can infer @Poly?
        polymorphicSerializerIfApplicableAutomatically()?.let { return it }
        // default serializable?
        if (shouldHaveGeneratedSerializer) {
            // $serializer nested class
            return this.unsubstitutedMemberScope
                .getDescriptorsFiltered(nameFilter = { it == SerialEntityNames.SERIALIZER_CLASS_NAME })
                .filterIsInstance<ClassDescriptor>().singleOrNull()
        }
        return null
    }

internal val ClassDescriptor.hasCompanionObjectAsSerializer: Boolean
    get() = isInternallySerializableObject || companionObjectDescriptor?.serializerForClass == this.defaultType

// returns only user-overriden Serializer
internal val KotlinType.overridenSerializer: KotlinType?
    get() {
        val desc = this.toClassDescriptor ?: return null
        desc.serializableWith?.let { return it }
        return null
    }

internal val KotlinType.genericIndex: Int?
    get() = (this.constructor.declarationDescriptor as? TypeParameterDescriptor)?.index

internal fun getSerializableClassDescriptorByCompanion(thisDescriptor: ClassDescriptor): ClassDescriptor? {
    if (thisDescriptor.isSerializableObject) return thisDescriptor
    if (!thisDescriptor.isCompanionObject) return null
    val classDescriptor = (thisDescriptor.containingDeclaration as? ClassDescriptor) ?: return null
    if (!classDescriptor.shouldHaveGeneratedMethodsInCompanion) return null
    return classDescriptor
}

internal fun ClassDescriptor.needSerializerFactory(): Boolean {
    if (!(this.platform?.isNative() == true || this.platform.isJs())) return false
    val serializableClass = getSerializableClassDescriptorByCompanion(this) ?: return false
    if (serializableClass.isSerializableObject) return true
    if (serializableClass.kind == ClassKind.ENUM_CLASS && !serializableClass.useLegacyEnums() && !serializableClass.hasSerializableAnnotationWithArgs) return true
    if (serializableClass.isAbstractOrSealedSerializableClass()) return true
    if (serializableClass.declaredTypeParameters.isEmpty()) return false
    return true
}

internal fun getSerializableClassDescriptorBySerializer(serializerDescriptor: ClassDescriptor): ClassDescriptor? {
    val serializerForClass = serializerDescriptor.serializerForClass
    if (serializerForClass != null) return serializerForClass.toClassDescriptor
    if (serializerDescriptor.name !in setOf(
            SerialEntityNames.SERIALIZER_CLASS_NAME,
            SerialEntityNames.GENERATED_SERIALIZER_CLASS
        )
    ) return null
    val classDescriptor = (serializerDescriptor.containingDeclaration as? ClassDescriptor) ?: return null
    if (!classDescriptor.shouldHaveGeneratedSerializer) return null
    return classDescriptor
}

internal fun ClassDescriptor.checkSerializableClassPropertyResult(prop: PropertyDescriptor): Boolean =
        prop.returnType!!.isSubtypeOf(getClassFromSerializationPackage(SerialEntityNames.SERIAL_DESCRIPTOR_CLASS).toSimpleType(false)) // todo: cache lookup

// todo: serialization: do an actual check better that just number of parameters
internal fun ClassDescriptor.checkSaveMethodParameters(parameters: List<ValueParameterDescriptor>): Boolean =
        parameters.size == 2

internal fun ClassDescriptor.checkSaveMethodResult(type: KotlinType): Boolean =
        KotlinBuiltIns.isUnit(type)

// todo: serialization: do an actual check better that just number of parameters
internal fun ClassDescriptor.checkLoadMethodParameters(parameters: List<ValueParameterDescriptor>): Boolean =
        parameters.size == 1

internal fun ClassDescriptor.checkLoadMethodResult(type: KotlinType): Boolean =
    getSerializableClassDescriptorBySerializer(this)?.defaultType == type
