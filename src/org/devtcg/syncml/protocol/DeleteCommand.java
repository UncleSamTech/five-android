/*
 * $Id$
 *
 * Copyright (C) 2008 Josh Guilfoyle <jasta@devtcg.org>
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */

package org.devtcg.syncml.protocol;

import java.io.IOException;
import org.xmlpull.v1.*;

public class DeleteCommand extends ItemActionCommand
{
	public DeleteCommand(XmlPullParser xpp)
	  throws XmlPullParserException, IOException
	{
		super("Delete", xpp);
	}
}