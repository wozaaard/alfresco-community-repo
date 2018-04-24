/*
 * #%L
 * Alfresco Records Management Module
 * %%
 * Copyright (C) 2005 - 2018 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * -
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 * -
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * -
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * -
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.rest.rm.community.model.audit;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.alfresco.utility.model.TestModel;

@Builder
@Data
@EqualsAndHashCode (callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties (ignoreUnknown = true)
public class AuditEntry extends TestModel
{
    @JsonProperty (required = true)
    private String nodeName;

    @JsonProperty (required = true)
    private List<Object> changedValues;

    @JsonProperty (required = true)
    private String identifier;

    @JsonProperty (required = true)
    private String path;

    @JsonProperty (required = true)
    private String nodeRef;

    @JsonProperty (required = true)
    private String fullName;

    @JsonProperty
    private String createPerson;

    @JsonProperty (required = true)
    private String userName;

    @JsonProperty (required = true)
    private String userRole;

    @JsonProperty (required = true)
    private String nodeType;

    @JsonProperty (required = true)
    private String event;

    @JsonProperty (required = true)
    private String timestamp;

}
