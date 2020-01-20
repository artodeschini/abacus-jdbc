/*
 * Copyright (C) 2020 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.util;

import java.sql.ResultSet;

/**
 * The Enum FetchDirection.
 */
public enum FetchDirection {

    /** The forward. */
    FORWARD(ResultSet.FETCH_FORWARD),
    /** The reverse. */
    REVERSE(ResultSet.FETCH_REVERSE),
    /** The unknown. */
    UNKNOWN(ResultSet.FETCH_UNKNOWN);

    /** The int value. */
    final int intValue;

    /**
     * Instantiates a new fetch direction.
     *
     * @param intValue
     */
    FetchDirection(int intValue) {
        this.intValue = intValue;
    }

    /**
     *
     * @param intValue
     * @return
     */
    public static FetchDirection valueOf(int intValue) {
        switch (intValue) {
            case ResultSet.FETCH_FORWARD:
                return FORWARD;

            case ResultSet.FETCH_REVERSE:
                return REVERSE;

            case ResultSet.FETCH_UNKNOWN:
                return UNKNOWN;

            default:
                throw new IllegalArgumentException("No FetchDirection mapping to int value: " + intValue);

        }
    }

    /**
     *
     * @return
     */
    public int intValue() {
        return intValue;
    }
}