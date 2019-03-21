package com.digitalctrl.jysk.core.workflow.impl;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "XML Product Processing Configuration", description = "Configuration to pass in template and location for XML Product Processing")
public @interface XMLProcessingConfiguration {
	
	@AttributeDefinition(name = "ContentFragment Template", description = "The path to the content fragment template to use to create product content fragments from xml entries")
	String contentFragmentTemplate();
}