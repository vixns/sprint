/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint
package model


case class LeadingApi(hostname: String, port: Int)
case class ApiStatus(leading: Boolean, leader: LeadingApi, version: String, gitCommit: String)
