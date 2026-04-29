/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.plugin.blackhole;

import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestBlackHoleConfig
{
    @Test
    public void testDefaults()
    {
        BlackHoleConfig config = new BlackHoleConfig();
        assertFalse(config.isCaseSensitiveNameMatching());
    }

    @Test
    public void testSetCaseSensitiveNameMatching()
    {
        BlackHoleConfig config = new BlackHoleConfig();
        config.setCaseSensitiveNameMatching(true);
        assertTrue(config.isCaseSensitiveNameMatching());

        config.setCaseSensitiveNameMatching(false);
        assertFalse(config.isCaseSensitiveNameMatching());
    }
}

// Made with Bob
