package io.github.loinguyen.bandwidth.compiler.fir

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.types.coneTypeOrNull

/** The return type identifies the constructed class, including local/inner classes. */
internal fun FirConstructor.initializationOwner(session: FirSession): FirClass? =
    returnTypeRef.coneTypeOrNull?.toClassSymbol(session)?.fir

/**
 * Ordinary source-class initialization is reached through constructor calls.
 * Named objects, companions, enums, and anonymous-object expressions need a
 * separate activation model and retain the existing eager-initialization guard.
 */
internal fun FirClass.hasModeledConstructorInitialization(): Boolean =
    origin.fromSource && classKind == ClassKind.CLASS && this !is FirAnonymousObject

/** Primary constructors may have no body node but still execute initializers. */
internal fun FirFunction.hasInferableNetworkBody(session: FirSession): Boolean =
    body != null || this is FirConstructor &&
        initializationOwner(session)?.hasModeledConstructorInitialization() == true
