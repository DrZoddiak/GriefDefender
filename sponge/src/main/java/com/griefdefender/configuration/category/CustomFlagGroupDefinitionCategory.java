/*
 * This file is part of GriefDefender, licensed under the MIT License (MIT).
 *
 * Copyright (c) bloodmc
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.griefdefender.configuration.category;

import java.util.HashMap;
import java.util.Map;

import com.griefdefender.permission.flag.GDFlagDefinition;
import com.griefdefender.permission.flag.GDFlagDefinitions;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class CustomFlagGroupDefinitionCategory extends ConfigCategory {

    @Setting
    Map<String, CustomFlagGroupCategory> groups = new HashMap<>();

    public Map<String, CustomFlagGroupCategory> getGroups() {
        return this.groups;
    }

    public void initDefaults() {
        CustomFlagGroupCategory userGroup = this.groups.get("user");
        CustomFlagGroupCategory adminGroup = this.groups.get("admin");
        if (userGroup == null) {
            userGroup = new CustomFlagGroupCategory();
        }
        if (userGroup.isEnabled() && userGroup.getFlagDefinitions().isEmpty()) {
            for (GDFlagDefinition definition : GDFlagDefinitions.USER_FLAGS) {
                userGroup.getFlagDefinitions().put(definition.getName(), definition);
            }
            this.groups.put("user", userGroup);
        }
        if (adminGroup == null) {
            adminGroup = new CustomFlagGroupCategory();
        }
        if (adminGroup.isEnabled() && adminGroup.getFlagDefinitions().isEmpty()) {
            for (GDFlagDefinition definition : GDFlagDefinitions.ADMIN_FLAGS) {
                adminGroup.getFlagDefinitions().put(definition.getName(), definition);
            }
            adminGroup.isAdmin = true;
            this.groups.put("admin", adminGroup);
        }
    }
}
