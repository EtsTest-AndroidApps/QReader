package com.ubadahj.qidianundergroud.utils.repositories

import android.content.Context
import com.ubadahj.qidianundergroud.models.ChapterGroup
import com.ubadahj.qidianundergroud.repositories.ChapterGroupRepository

fun ChapterGroup.getBook(context: Context) =
    ChapterGroupRepository(context).getBook(this)

