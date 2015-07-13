/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.client;

import android.support.annotation.NonNull;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.LofiGalleryInfo;
import com.hippo.ehviewer.util.EhUtils;
import com.hippo.yorozuya.AssertException;
import com.hippo.yorozuya.AssertUtils;
import com.hippo.yorozuya.NumberUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GalleryListParser {

    public static class Result {

        public static final int NOT_FOUND = 0;

        public static final int CURRENT_PAGE_IS_LAST = -1;

        public static final int KEEP_LOADING = Integer.MAX_VALUE;

        /**
         * If NOT FOUND, pageNum is 0.<br>
         * For lofi, we can not get pages number,
         * so pageNum is {@link java.lang.Integer#MAX_VALUE} when it is not the last page,
         * pageNum is {@link #CURRENT_PAGE_IS_LAST} when it is the last page
         */
        public int pages;
        public List<GalleryInfo> galleryInfos;
    }

    public static Result parse(@NonNull String body, int source) throws Exception {
        switch (source) {
            default:
            case EhUrl.SOURCE_G:
            case EhUrl.SOURCE_EX: {
                return parse(body);
            }
            case EhUrl.SOURCE_LOFI: {
                return parseLofi(body);
            }
        }
    }

    private static Result parse(String body) throws EhException {
        Result result = new Result();
        Pattern p;
        Matcher m;

        // pages
        p = Pattern.compile("<a[^<>]+>([\\d]+)</a></td><td[^<>]+>(?:<a[^<>]+>)?&");
        m = p.matcher(body);
        if (m.find()) {
            result.pages = ParserUtils.parseInt(m.group(1));
        } else if (body.contains("No hits found</p>")) {
            result.pages = Result.NOT_FOUND;
        } else {
            // Can not get page number
            throw new ParseException("Can't parse gallery list", body);
        }

        if (result.pages > 0) {
            List<GalleryInfo> list = new ArrayList<>(25);
            result.galleryInfos = list;

            p = Pattern.compile("<td class=\"itdc\">(?:<a.+?>)?<img.+?alt=\"(.+?)\".+?/>(?:</a>)?</td>" // category
                    + "<td.+?>(.+?)</td>" // posted
                    + "<td.+?><div.+?><div.+?height:(\\d+)px; width:(\\d+)px\">"
                    + "(?:<img.+?src=\"(.+?)\".+?alt=\"(.+?)\" style.+?/>"
                    + "|init~([^<>\"~]+~[^<>\"~]+)~([^<>]+))" // thumb and title
                    + "</div>"
                    + ".+?"
                    + "<div class=\"it5\"><a href=\"([^<>\"]+)\"[^<>]+>(.+?)</a></div>" // url and title
                    + ".+?"
                    + "<div class=\"ir it4r\" style=\"([^<>\"]+)\">" // rating
                    + ".+?"
                    + "<td class=\"itu\"><div><a.+?>(.+?)</a>"); // uploader
            m = p.matcher(body);
            while (m.find()) {
                GalleryInfo gi = new GalleryInfo();

                gi.category = EhUtils.getCategory(ParserUtils.trim(m.group(1)));
                gi.posted = ParserUtils.trim(m.group(2));
                gi.thumbHeight = ParserUtils.parseInt(m.group(3));
                gi.thumbWidth = ParserUtils.parseInt(m.group(4));

                if (m.group(5) == null) {
                    gi.thumb = ParserUtils.trim("http://" + m.group(7).replace('~', '/'));
                    gi.title = ParserUtils.trim(m.group(8));
                } else {
                    gi.thumb = ParserUtils.trim(m.group(5));
                    gi.title = ParserUtils.trim(m.group(6));
                }

                Pattern pattern = Pattern
                        .compile("/(\\d+)/(\\w+)");
                Matcher matcher = pattern.matcher(m.group(9));
                if (matcher.find()) {
                    gi.gid = ParserUtils.parseInt(matcher.group(1));
                    gi.token = ParserUtils.trim(matcher.group(2));
                } else {
                    continue;
                }

                gi.rating = NumberUtils.parseFloatSafely(getRate(m.group(11)), Float.NaN);
                gi.uploader = ParserUtils.trim(m.group(12));
                gi.generateSLang();

                list.add(gi);
            }

            if (list.size() == 0) {
                throw new ParseException("Can't parse gallery list", body);
            }
        }

        return result;
    }

    private static Result parseLofi(String body) throws Exception {
        Result result = new Result();
        Pattern p;
        Matcher m;

        List<GalleryInfo> list = new ArrayList<>(25);
        result.galleryInfos = list;
        p = Pattern.compile("<td class=\"ii\"><a href=\"(.+?)\">" // detail url
                + "<img src=\"(.+?)\".+?/>" // thumb url
                + ".+?<a class=\"b\" href=\".+?\">(.+?)</a>" // title
                + ".+?<td class=\"ik ip\">Posted:</td><td class=\"ip\">(.+?)</td>" // Posted and uploader
                + "</tr><tr><td class=\"ik\">Category:</td><td>(.+?)</td>" // Category
                + "</tr><tr><td class=\"ik\">Tags:</td><td>(.+?)</td>" // Tags
                + "</tr><tr><td class=\"ik\">Rating:</td><td class=\"ir\">(.+?)</td>"); // rating
        m = p.matcher(body);
        DetailUrlParser dup = new DetailUrlParser();
        String[] pau = new String[2];
        while (m.find()) {
            LofiGalleryInfo lgi = new LofiGalleryInfo();

            dup.parser(m.group(1));
            lgi.gid = dup.gid;
            lgi.token = dup.token;

            lgi.thumb = ParserUtils.trim(m.group(2));
            lgi.title = ParserUtils.trim(m.group(3));

            getPostedAndUploader(m.group(4), pau);
            lgi.posted = pau[0];
            lgi.uploader = pau[1];

            lgi.category = EhUtils.getCategory(m.group(5));
            String tags = m.group(6);
            if (tags.equals("-"))
                lgi.lofiTags = new String[0];
            else
                lgi.lofiTags = tags.split(", ");
            String rating = m.group(7);
            if (rating.equals("-"))
                lgi.rating = Float.NaN;
            else
                lgi.rating = getStartNum(rating);
            lgi.generateSLang();

            list.add(lgi);
        }

        if (list.size() == 0) {
            if (body.contains("No hits found</div>")) {
                result.pages = Result.NOT_FOUND;
            } else if (body.contains("No more hits found</div>")) {
                throw new EhException("Index is out of range");
            } else {
                throw new ParseException("Can't parse gallery list", body);
            }
        } else {
            if (!body.contains("Next Page &gt;</a>")) {
                result.pages = Result.CURRENT_PAGE_IS_LAST;
            } else {
                result.pages = Result.KEEP_LOADING;
            }
        }

        return result;
    }

    private static String getRate(String rawRate) {
        Pattern p = Pattern.compile("\\d+px");
        Matcher m = p.matcher(rawRate);
        int num1;
        int num2;
        int rate = 5;
        String re;
        if (m.find())
            num1 = ParserUtils.parseInt(m.group().replace("px", ""));
        else
            return null;
        if (m.find())
            num2 = ParserUtils.parseInt(m.group().replace("px", ""));
        else
            return null;
        rate = rate - num1 / 16;
        if (num2 == 21) {
            rate--;
            re = Integer.toString(rate);
            re = re + ".5";
        } else
            re = Integer.toString(rate);
        return re;
    }

    private static final String PAU_SPACER = " by ";

    private static void getPostedAndUploader(String raw, String[] pau) throws AssertException {
        int index = raw.indexOf(PAU_SPACER);
        AssertUtils.assertNotEqualsEx("Can not parse posted and uploader", index, -1);
        pau[0] = raw.substring(0, index);
        pau[1] = raw.substring(index + PAU_SPACER.length());
    }

    private static int getStartNum(String str) {
        int startNum = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == '*')
                startNum++;
        }
        return startNum;
    }
}
