import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
	jvm()
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    
    sourceSets {
        commonMain.dependencies {
			implementation(project.dependencies.platform(libs.koin.bom))
	        implementation(libs.koin.core)
	        implementation(libs.koin.annotations)
	        
	        implementation(libs.kotlinx.serialization.json)
	        
	        // Arrow 2.x supports all KMP targets including Wasm
	        // Use api() because Either is part of our public API (OperationResult)
		    api(libs.arrow.core)
	        implementation(libs.arrow.fx.coroutines)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
	        implementation(libs.koin.test)
        }
	    jvmMain.dependencies {
	     
		    implementation(project.dependencies.platform(libs.dss.bom))
		    
		    implementation(libs.dss.document)
		    implementation(libs.dss.model)
		    
		    implementation(libs.dss.pades)
		    implementation(libs.dss.pades.pdfbox)
		    
		    implementation(libs.dss.enumerations)
		    implementation(libs.dss.alert)
		    implementation(libs.dss.utils.apache.commons)
		    implementation(libs.dss.i18n)
		    
		    implementation(libs.dss.crl.parser)
		    implementation(libs.dss.crl.parser.stream)
		    implementation(libs.dss.token)
		    implementation(libs.dss.pdfa)
		    
		    implementation(libs.dss.service) // handles TSP, OCSP, CRL requests, etc.
		    implementation(libs.dss.timestamp.remote)
		    implementation(libs.dss.validation)
		    
		    implementation(libs.dss.policy.jaxb)
		    implementation(libs.dss.policy.crypto.xml)
		    implementation(libs.dss.policy.crypto.json)
		    
		    implementation(libs.jaxb.runtime)
	    }
    }
}






