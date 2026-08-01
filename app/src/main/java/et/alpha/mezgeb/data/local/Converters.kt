package et.alpha.mezgeb.data.local

import androidx.room.TypeConverter
import et.alpha.mezgeb.domain.model.CaptureSource
import et.alpha.mezgeb.domain.model.CategoryKind
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.MatchField
import et.alpha.mezgeb.domain.model.MatchType
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.domain.model.SessionStatus

/**
 * Enum converters, and nothing else.
 *
 * Enums are stored by `name`, not by ordinal: reordering a member must never silently
 * reinterpret existing rows. Money and timestamps are already primitives and need no
 * conversion — if a converter ever appears here for a money type, something has gone
 * wrong upstream.
 */
class Converters {

    @TypeConverter fun captureSourceToString(value: CaptureSource): String = value.name

    @TypeConverter fun stringToCaptureSource(value: String): CaptureSource =
        CaptureSource.valueOf(value)

    @TypeConverter fun parseStateToString(value: ParseState): String = value.name

    @TypeConverter fun stringToParseState(value: String): ParseState = ParseState.valueOf(value)

    @TypeConverter fun directionToString(value: Direction): String = value.name

    @TypeConverter fun stringToDirection(value: String): Direction = Direction.valueOf(value)

    @TypeConverter fun channelToString(value: Channel): String = value.name

    @TypeConverter fun stringToChannel(value: String): Channel = Channel.valueOf(value)

    @TypeConverter fun creditKindToString(value: CreditKind): String = value.name

    @TypeConverter fun stringToCreditKind(value: String): CreditKind = CreditKind.valueOf(value)

    @TypeConverter fun categoryKindToString(value: CategoryKind): String = value.name

    @TypeConverter fun stringToCategoryKind(value: String): CategoryKind =
        CategoryKind.valueOf(value)

    @TypeConverter fun matchFieldToString(value: MatchField): String = value.name

    @TypeConverter fun stringToMatchField(value: String): MatchField = MatchField.valueOf(value)

    @TypeConverter fun matchTypeToString(value: MatchType): String = value.name

    @TypeConverter fun stringToMatchType(value: String): MatchType = MatchType.valueOf(value)

    @TypeConverter fun sessionStatusToString(value: SessionStatus): String = value.name

    @TypeConverter fun stringToSessionStatus(value: String): SessionStatus =
        SessionStatus.valueOf(value)
}
