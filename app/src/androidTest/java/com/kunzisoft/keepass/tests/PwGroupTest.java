/*
 * Copyright 2017 Brian Pellin, Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePass DX.
 *
 *  KeePass DX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePass DX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePass DX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.tests;


import android.test.AndroidTestCase;

import com.kunzisoft.keepass.database.element.PwGroupV3;
import com.kunzisoft.keepass.tests.database.TestData;

public class PwGroupTest extends AndroidTestCase {

    PwGroupV3 mPG;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        //mPG = (PwGroupV3) TestData.GetTest1(getContext()).getGroups().get(0);

    }

    public void testGroupName() {
        //assertTrue("Name was " + mPG.getTitle(), mPG.getTitle().equals("Internet"));
    }
}

