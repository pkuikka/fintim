package com.example.fintim.data

import android.util.Log
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import java.nio.charset.StandardCharsets
import kotlin.Int

const val MAX_TIMER_DATA_ROWS = 16
const val MAX_DATA_SETS = 10
const val MAX_TIME_TENTHS_LIMIT = 25
const val TIMER_TYPE_F1Q = 1
const val TIMER_TYPE_F1B = 2
// const val TIMER_TYPE_F1A = 3

/**
 * Represents the complete set of timer configuration data.
 * The @Serializable annotation allows this class to be converted to/from JSON.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
data class TimerData(
    // Timerin kentät on selitetty tämän tiedoston lopussa. Ne esitetään 256 ascii tauluna,
    // mutta todellisuudessa kommunikaation läyttää 512 ascii taulua ja jotkin 1 bytem arvot on
    // luettava suoraan 512 taulusta
    val timerVersion: String = "1", // näyttäisi olevan aina sama
    val modelType: Int = TIMER_TYPE_F1Q, // F1Q = 1, 2 = F1B (ei oikeasti ole timerissa tallessa)
    val modelId: Int = 0,
    val modelSet: Int = 0,

    // timer näytön pääarvot, 2 + 16 riviä
    val timeValues: List<Double> = List(MAX_TIMER_DATA_ROWS) { it.toDouble() },
    val servo1Values: List<Int> = List(MAX_TIMER_DATA_ROWS) { 125 },
    val servo2Values: List<Int> = List(MAX_TIMER_DATA_ROWS) { 126 },
    val servo3Values: List<Int> = List(MAX_TIMER_DATA_ROWS) { 127 },
    val servo4Values: List<Int> = List(MAX_TIMER_DATA_ROWS) { 128 },
    val stepValues: List<Int> = List(MAX_TIMER_DATA_ROWS) { if (it == MAX_TIMER_DATA_ROWS - 1) 2 else 0 },

    val servoStandByValues: List<Int> = List(4) { 123 }, // 25 - 28 = Servo 1 - 4 temperature range
    val servoStartValues: List<Int> = List(4) { 124 }, // 25 - 28 = Servo 1 - 4 temperature range

    // servo näytön arvot
    val servoTrimPosition: List<Int> = List(4) { 127 },
    val servoReversed: List<Int> = List(4) { 0 },
    val servoMinPosition: List<Int> = List(4) { 127 },
    val servoMaxPosition: List<Int> = List(4) { 127 },
    val limiterStop: Int = 7,
    //val limiterStop2: Int = 7,
    val propStopDelay: Double = 0.0,
    val propStopServo5Release: Int = 127,
    val propStopServo5Stop: Int = 127,
    val flashEnabled: Int = 0,

    // patterin jännite joka tulee omalla kutsulla
    val batteryVoltage: Float = 0f,

    // alkuperäinen timeristä luettu 512 ascii data, käytetään "write" ooeraation pohjanana aina
    val dataAs512Ascii: String = "",

    // applikaatiossa käytettävä data, jota ei lähetetä timeriin
    val numberOfDataRows: Int = 8,
    val maxDataRows: Int = MAX_TIMER_DATA_ROWS,
    val writeTimeStamp: String = "2501010101",
    val modelName: String = "Default Name",

    val servo1Label: String = "Stab",
    val servo2Label: String = "Rudd",
    val servo3Label: String = "Wigl",
    val servo4Label: String = "ESC",

    val row1Label: String = "Standby",
    val row2Label: String = "Start",

    val comments: String = "",
    val batteryType: String = "S3",

    val setNames: List<String> = List(MAX_DATA_SETS) { "" },
    val setTimeValues: List<List<Double>> = List(MAX_DATA_SETS) { List(MAX_TIMER_DATA_ROWS) { it.toDouble() * 2.0 } },
    val setServo1Values: List<List<Int>> = List(MAX_DATA_SETS) { List(MAX_TIMER_DATA_ROWS) { 111 } },
    val setServo2Values: List<List<Int>> = List(MAX_DATA_SETS) { List(MAX_TIMER_DATA_ROWS) { 112 } },
    val setServo3Values: List<List<Int>> = List(MAX_DATA_SETS) { List(MAX_TIMER_DATA_ROWS) { 113 } },
    val setServo4Values: List<List<Int>> = List(MAX_DATA_SETS) { List(MAX_TIMER_DATA_ROWS) { 114 } },

    ) {

    companion object {
        /**
         * Factory function to create a TimerData object from the raw 256-byte packet
         * received from the timer device.
         *
         * @param packetBytes The 256-byte ByteArray from the device.
         * @return A fully populated TimerData object.
         * @throws IllegalArgumentException if the packet is not 256 bytes long.
         */
        fun fromPacket(packetBytes: ByteArray): TimerData {
            // Varmista, että data on 512 kokoinen
            require(packetBytes.size == 512) { "Packet must be 512 bytes long." }

            // Muuta ascii muotoon
            val asciiString = String(packetBytes, StandardCharsets.US_ASCII)
            Log.i("UiViewModel", "Ascii 512 data: $asciiString")

            // huomio: index on tässä suoraan 512 kokoinen eli paikka pitää olla *2
            fun getInt16From1AsciiString(index: Int): Int = asciiString[index].digitToInt(16)

            fun getInt16From2AsciiString(index: Int): Int {
                val asciiIndex = index * 2
                return asciiString.substring(asciiIndex, asciiIndex + 2).toInt(16)
            }

            fun getInt16From4AsciiString(index: Int): Int {
                val asciiIndex = index * 2
                return asciiString.substring(asciiIndex, asciiIndex + 4).toInt(16)
            }

            // timerin pääkentät (16 riviä)
            val indices = 0 until MAX_TIMER_DATA_ROWS
            val timeValues = indices.map { i -> getInt16From4AsciiString(40 + (i * 2)) * 0.02 }
            val servo1Values = indices.map { i -> getInt16From4AsciiString(96 + (i * 8)) }
            val servo2Values = indices.map { i -> getInt16From4AsciiString(98 + (i * 8)) }
            val servo3Values = indices.map { i -> getInt16From4AsciiString(100 + (i * 8)) }
            val servo4Values = indices.map { i -> getInt16From4AsciiString(102 + (i * 8)) }
            val stepValues = indices.map { i -> getInt16From1AsciiString(15 * 2 + i) } // huomio, oikea paikka *2

            fun getFirstRowWithD(): Int {
                for (i in 0 until MAX_TIMER_DATA_ROWS) {
                    if (stepValues[i] == 2) {
                        return i+1
                    }
                }
                return MAX_TIMER_DATA_ROWS
            }

            // päivitä kaikki timerDatan kentät timeristä tulevilla arvoilla
            val timerData = TimerData(
                timerVersion = getInt16From4AsciiString(0).toString(),
                modelType = TIMER_TYPE_F1Q, // kova koodattu vain Q tuki
                modelId = getInt16From4AsciiString(13),
                modelName = getInt16From4AsciiString(13).toString(),  // laitetaan ID aina nimeksi
                modelSet = 1,

                numberOfDataRows = minOf(getFirstRowWithD(), MAX_TIMER_DATA_ROWS),

                // timer näytön pääarvot, 2 + 16 riviä
                timeValues = timeValues,
                servo1Values = servo1Values,
                servo2Values = servo2Values,
                servo3Values = servo3Values,
                servo4Values = servo4Values,
                stepValues = stepValues,

                servoStandByValues = listOf(224, 226, 228, 230
                ).map { getInt16From4AsciiString(it) }, //standby arvot

                servoStartValues = listOf(248, 250, 252, 254
                ).map { getInt16From4AsciiString(it) }, //start arvot

                // servo näytön arvot
                servoReversed = listOf(4, 5, 6, 7
                ).map { getInt16From1AsciiString(it) }, //onko servo käännetty

                servoTrimPosition = listOf(36, 37, 38, 39
                ).map { getInt16From2AsciiString(it) }, //servo trimmi arvot

                servoMinPosition = listOf(72, 76, 80, 84
                ).map { getInt16From4AsciiString(it) }, //servo min position arvot

                servoMaxPosition = listOf(74, 78, 82, 86
                ).map { getInt16From4AsciiString(it) }, //servo max position arvot

                limiterStop = getInt16From1AsciiString(66) + 1, //rivi mihin hypätään limiter stopin jälkeen, käyttää vain ekan merkin bytestä ja tarvii +1

                propStopDelay = getInt16From2AsciiString(30).toDouble() / 100.00, //
                propStopServo5Release = getInt16From4AsciiString(238), //
                propStopServo5Stop = getInt16From4AsciiString(246), //
                flashEnabled = getInt16From1AsciiString(17), //

                // talleta alkuperäinen 512 rivi, koska kaikkia kenttiä ei käsitellä
                // tätä käytetään aina pohjana kirjoittaessa
                dataAs512Ascii = asciiString,
            )
            return timerData
        }

        fun createNew(timerType: Int): TimerData {
            val defaultTimerData = TimerData()
            val numberOfRows = 6
            return when (timerType) {
                TIMER_TYPE_F1Q -> {
                    val newStepValues = defaultTimerData.stepValues.toMutableList().apply {
                        this[numberOfRows - 1] = 2
                        this[MAX_TIMER_DATA_ROWS - 1] = 2
                    }
                    defaultTimerData.copy(
                        modelType = TIMER_TYPE_F1Q,
                        modelName = "F1Q Timer Example",
                        numberOfDataRows = numberOfRows,
                        stepValues = newStepValues,
                        propStopDelay = 0.5,
                        flashEnabled = 1
                    )
                }

                TIMER_TYPE_F1B -> {
                    val newStepValues = defaultTimerData.stepValues.toMutableList().apply {
                        this[5] = 2
                        this[MAX_TIMER_DATA_ROWS - 1] = 2
                    }
                    defaultTimerData.copy(
                        modelType = TIMER_TYPE_F1B,
                        modelName = "F1B Timer Example",
                        numberOfDataRows = 6,
                        stepValues = newStepValues,
                        flashEnabled = 1
                    )
                }

                else -> {
                    // It's good practice to handle the unknown case
                    defaultTimerData
                }
            }
        }
    }

    /**
     * Serializes the TimerData object back into a 262-byte ByteArray
     * to be written to the timer device.
     *
     * @return A 262-byte ByteArray representing the current state of this object.
     */
    fun toPacket(): ByteArray {
        var packet = ByteArray(512)

        // huomio: index on tässä suoraan 512 kokoinen eli paikka pitää olla *2
        fun set1toAscii(index: Int, value: Int) {
            if (index < packet.size) {
                packet[index] = value.digitToChar(16).uppercaseChar().code.toByte()
                Log.i("UiViewModel", "Write data 1 char: $index = $value")
            }
        }

        fun set2toAscii(index256: Int, value: Int) {
            val index512 = index256 * 2
            if ((index512 + 1) < packet.size) {
                val hexString = value.toString(16).padStart(2, '0').uppercase()
                packet[index512] = hexString[0].code.toByte()
                packet[index512 + 1] = hexString[1].code.toByte()
                Log.i("UiViewModel", "Write data: $index512 = $hexString")
            }
        }

        fun set4toAscii(index256: Int, value: Int) {
            val index512 = index256 * 2
            if ((index512 + 3) < packet.size) {
                val hexString = value.toString(16).padStart(4, '0').uppercase()
                packet[index512] = hexString[0].code.toByte()
                packet[index512 + 1] = hexString[1].code.toByte()
                packet[index512 + 2] = hexString[2].code.toByte()
                packet[index512 + 3] = hexString[3].code.toByte()
                Log.i("UiViewModel", "Write data: $index512 = $hexString")
            }
        }

        fun set4toTimeAscii(index256: Int, value: Double) {
            val index512 = index256 * 2
            val valueAsInt = (value * 50).toInt()

            if ((index512 + 3) < packet.size) {
                val hexString = valueAsInt.toString(16).padStart(4, '0').uppercase()
                packet[index512] = hexString[0].code.toByte()
                packet[index512 + 1] = hexString[1].code.toByte()
                packet[index512 + 2] = hexString[2].code.toByte()
                packet[index512 + 3] = hexString[3].code.toByte()
                Log.i("UiViewModel", "Write data (time): $index512 = $hexString")
            }
        }

        // Laita luettu merkkijono pohjaksi kirjoitettavalle tiedolle
        packet = dataAs512Ascii.toByteArray(Charsets.US_ASCII)

        set4toAscii(13, modelId)

        set1toAscii(4, servoReversed[0])
        set1toAscii(5, servoReversed[1])
        set1toAscii(6, servoReversed[2])
        set1toAscii(7, servoReversed[3])

        set1toAscii(66, limiterStop - 1) // käyttää vain ekaa merkkiä bytestä ja vaatii -1
        set2toAscii(30, (propStopDelay * 100).toInt()) //
        set4toAscii(238, propStopServo5Release) //
        set4toAscii(246, propStopServo5Stop) //
        set1toAscii(17, flashEnabled) //

        for (i in 0 until MAX_TIMER_DATA_ROWS) {
            set4toTimeAscii(40 + i * 2, timeValues[i])
            set4toAscii(96 + i * 8, servo1Values[i])
            set4toAscii(98 + i * 8, servo2Values[i])
            set4toAscii(100 + i * 8, servo3Values[i])
            set4toAscii(102 + i * 8, servo4Values[i])
            set1toAscii(15 * 2 + i, stepValues[i])
        }

        //servoTrimPosition = listOf(36, 37, 38, 39).map { getInt16From4AsciiString(it) }, //servo trimmi arvot
        set2toAscii(36, servoTrimPosition[0])
        set2toAscii(37, servoTrimPosition[1])
        set2toAscii(38, servoTrimPosition[2])
        set2toAscii(39, servoTrimPosition[3])

        //servoMinPosition = listOf(72, 76, 80, 84).map { getInt16From4AsciiString(it) }, //min position arvot
        set4toAscii(72, servoMinPosition[0])
        set4toAscii(76, servoMinPosition[1])
        set4toAscii(80, servoMinPosition[2])
        set4toAscii(84, servoMinPosition[3])

        //servoMaxPosition = listOf(74, 78, 82, 86).map { getInt16From4AsciiString(it) }, //max position arvot
        set4toAscii(74, servoMaxPosition[0])
        set4toAscii(78, servoMaxPosition[1])
        set4toAscii(82, servoMaxPosition[2])
        set4toAscii(86, servoMaxPosition[3])

        //servoStandByValues = listOf(224, 226, 228, 230).map { getInt16From4AsciiString(it) }, //standby arvot
        set4toAscii(224, servoStandByValues[0])
        set4toAscii(226, servoStandByValues[1])
        set4toAscii(228, servoStandByValues[2])
        set4toAscii(230, servoStandByValues[3])

        //servoStartValues = listOf(248, 250, 252, 254).map { getInt16From4AsciiString(it) }, //start arvot
        set4toAscii(248, servoStartValues[0])
        set4toAscii(250, servoStartValues[1])
        set4toAscii(252, servoStartValues[2])
        set4toAscii(254, servoStartValues[3])

        return packet
    }
}

@Serializable
data class TimerDataSetsWrapper(
    val setNames: List<String> = List(MAX_DATA_SETS) { "" },
    val setTimeValues: List<List<Double>> = List(MAX_DATA_SETS) { List(MAX_TIMER_DATA_ROWS) { it.toDouble() * 2.0 } },
    val setServo1Values: List<List<Int>> = List(MAX_DATA_SETS) { List(MAX_TIMER_DATA_ROWS) { 111 } },
    val setServo2Values: List<List<Int>> = List(MAX_DATA_SETS) { List(MAX_TIMER_DATA_ROWS) { 112 } },
    val setServo3Values: List<List<Int>> = List(MAX_DATA_SETS) { List(MAX_TIMER_DATA_ROWS) { 113 } },
    val setServo4Values: List<List<Int>> = List(MAX_DATA_SETS) { List(MAX_TIMER_DATA_ROWS) { 114 } }
)



/*
// Timerin muuttujat 256 Hex muodossa. Huom! kommunikaation on 512 merkin ascii merkkijono
// ja osa arvoista pitää lukea tuosta 512 ascii jonosta, koska kahden paikan yhdistäminen yhdeksi
// arvoksi hävittää tietos

    ;Muut
    ;	        equ		0x100	0	;Timeri-ohjelma				Ei käytössä
    ;	        equ		0x101	1	;Timeri-ohjelman versio				Ei käytössä
    S1S2KaTs	equ		0x102	2	;Serv1/Serv2 käännetty toimisuunta
    S3S4KaTs	equ		0x103	3	;Serv3/Serv4 käännetty toimisuunta
    S5VaKaTs	equ		0x104	4	;Serv5/Vara  käännetty toimisuunta
    EtAaEtVa	equ		0x105	5	;Etsintä-ääni/Etsintävalo valittu
    SerAs255999	equ		0x106	6	;Servo-asennot 255/999				Ei käytössä
    SerTesTrim	equ		0x107	7	;Servojen testaus-/trimmi-toiminta valittu
    BatLeVa	    equ		0x108	8	;Battery-/Lentovalo valittu (flash Android versiossa)
    Var1Var2	equ		0x109	9	;Varalla1/Varalla2 valittu
    Var3Var4	equ		0x10A	10	;Varalla3/Varalla4 valittu
    ;       	equ		0x10B	11	;
    ;       	equ		0x10C	12	;
    ;	        equ		0x10D	13	;tallennetaan koneen ID (ei käytetä timerissä)

    ;Ympyrähinausviive 0x00...0x06 (0...6s)
    YmpyrViiv	equ		0x10E	14	;Ympyrähinausviive

    ;Askeltapa 0=Step, 1=Ramp, 2=DT, 3=Accel
    AskTapa12	equ		0x10F	15	;Askeltapa, askeleet 1, 2 (luettava 512 ascii jonosta)
    AskTapa34	equ		0x110	16	;Askeltapa, askeleet 3, 4
    AskTapa56	equ		0x111	17	;Askeltapa, askeleet 5, 6
    AskTapa78	equ		0x112	18	;Askeltapa, askeleet 7, 8
    AskTapa910	equ		0x113	19	;Askeltapa, askeleet 9, 10
    AskTapa1112	equ		0x114	20	;Askeltapa, askeleet 11, 12
    AskTapa1314	equ		0x115	21	;Askeltapa, askeleet 13, 14
    AskTapa1516	equ		0x116	22	;Askeltapa, askeleet 15, 16

    ;Hinausaskel-osoite 0x0...0xF (1...16)
    YmSuHinAsk	equ		0x117	23	;Ympyrä- ja suora-hinausaskel
    ;Hinausaika-asetus 0x0000...0x03E7 (0...999s)
    YmpHinAikH	equ		0x118	24	;Ympyrähinausaika-asetus
    YmpHinAikL	equ		0x119	25	;
    SuorHinH	equ		0x11A	26	;Suorahinausaika-asetus
    SuorHinL	equ		0x11B	27	;
    ;Kiihdytysaskel-osoite 0x0...0xF (1...16)
    LyPiKiihAsk	equ		0x11C	28	;Lyhyt- ja pitkä-kiihdytysaskel
    ;Kiihdytysaika-asetus 0x0000...0x03E7 (0.00...9.99s)
    LyhKiihdH	equ		0x11D	29	;Lyhytkiihdytysaika-asetus
    LyhKiihdL	equ		0x11E	30	;Nyt käytössä PropStop-viive 0x0-0xFF (0-2.55 s)
    PitKiihdH	equ		0x11F	31	;Pitkäkiihdytysaika-asetus
    PitKiihdL	equ		0x120	32	;
    ;Ulkopuolinen ohjausaskel-osoite 0x00...0x0F (1...16)
    UpOhAsk	    equ		0x121	33	;Ulkopuolinen ohjausaskel / limiter pysäytys

    ;Suunnanvaihtovälys 0x0...0xF (0...15us)
    KoSiSv	equ		0x122	34	;Serv1/Serv2 suunnanvaihtovälys
    V1V2Sv	equ		0x123	35	;Serv3/Serv4 suunnanvaihtovälys

    ;Trimmi-arvo 0x00...0xFF (0...255)
    Serv1Trim	equ		0x124	36	;Serv1 trimmi-arvo
    Serv2Trim	equ		0x125	37	;Serv2 trimmi-arvo
    Serv3Trim	equ		0x126	38	;Serv3 trimmi-arvo
    Serv4Trim	equ		0x127	39	;Serv4 trimmi-arvo

    ;Aika-asetus 0x0000...0xC34F (0...999.98s / 49999 x 0,02s)
    Aika1H	    equ		0x128	40	;Aika 1
    Aika1L	    equ		0x129	41	;
    Aika2H	    equ		0x12A	42	;Aika 2
    Aika2L	    equ		0x12B	43	;
        ...
    Aika16H	    equ		0x146	70	;Aika 16
    Aika16L	    equ		0x147	71	;

    ;Servorajat, asentoarvo  0x0000...0x0FFF (0...4095us)
    Serv1MinAsH	equ		0x148	72	;Minimi- ja maksimi-asennot
    Serv1MinAsL	equ		0x149	73	;
    Serv1MaxAsH	equ		0x14A	74	;
    Serv1MaxAsL	equ		0x14B	75	;
    Serv2MinAsH	equ		0x14C	76	;
        ...
    Serv4MaxAsL	equ		0x157	87	;

    ;ServTest-toiminta, asentoasetus 0x0000...0x03E7 (0...999)
    Serv1StAsH	equ		0x158	88	;ServTest-asennot
    Serv1StAsL	equ		0x159	89	;
    Serv2StAsH	equ		0x15A	90	;
    Serv2StAsL	equ		0x15B	91	;
    Serv3StAsH	equ		0x15C	92	;
    Serv3StAsL	equ		0x15D	93	;
    Serv4StAsH	equ		0x15E	94	;
    Serv4StAsL	equ		0x15F	95	;

    ;Aikajono, asentoasetus 0x0000...0x03E7 (0...999)
    Serv1As1H	equ		0x160	96	;Asennot 1
    Serv1As1L	equ		0x161	97	;
    Serv2As1H	equ		0x162	98	;
    Serv2As1L	equ		0x163	99	;
    Serv3As1H	equ		0x164	100	;
    Serv3As1L	equ		0x165	101	;
    Serv4As1H	equ		0x166	102	;
    Serv4As1L	equ		0x167	103	;
    Serv1As2H	equ		0x168	104	;Asennot 2
        ...
    Serv1As16H	equ		0x1D8	216	;Asennot 16
    Serv1As16L	equ		0x1D9	217	;
    Serv2As16H	equ		0x1DA	218	;
    Serv2As16L	equ		0x1DB	219	;
    Serv3As16H	equ		0x1DC	220	;
    Serv3As16L	equ		0x1DD	221	;
    Serv4As16H	equ		0x1DE	222	;
    Serv4As16L	equ		0x1DF	223	;

    ;Hinaus, asentoasetus 0x0000...0x03E7 (0...999)
    Serv1HiAs1H	equ		0x1E0	224	;Suorahinausasennot 1 - B/Q: Valmiusasennot
    Serv1HiAs1L	equ		0x1E1	225	;
    Serv2HiAs1H	equ		0x1E2	226	;
    Serv2HiAs1L	equ		0x1E3	227	;
    Serv3HiAs1H	equ		0x1E4	228	;
    Serv3HiAs1L	equ		0x1E5	229	;
    Serv4HiAs1H	equ		0x1E6	230	;
    Serv4HiAs1L	equ		0x1E7	231	;
    Serv1HiAs2H	equ		0x1E8	232	;Ynpyrähinausasennot 2
    Serv1HiAs2L	equ		0x1E9	233	;
    Serv2HiAs2H	equ		0x1EA	234	;
    Serv2HiAs2L	equ		0x1EB	235	;
    Serv3HiAs2H	equ		0x1EC	236	;
    Serv3HiAs2L	equ		0x1ED	237	;

    PropRelAsH	equ		0x1EE	238	;Prop Release asento, servo 5
    PropRelAsL	equ		0x1EF	239	;

    Serv1HiAs3H	equ		0x1F0	240	;Käännöshinausasennot 3
    Serv1HiAs3L	equ		0x1F1	241	;
    Serv2HiAs3H	equ		0x1F2	242	;
    Serv2HiAs3L	equ		0x1F3	243	;
    Serv3HiAs3H	equ		0x1F4	244	;
    Serv3HiAs3L	equ		0x1F5	245	;

    PropStopAsH	equ		0x1F6	246	;Prop Stop asento, servo 5
    PropStopAsL	equ		0x1F7	247	;

    Serv1HiAs4H	equ		0x1F8	248	;Kiihdytyshinausasennot 4 - B/Q Start-asennot
    Serv1HiAs4L	equ		0x1F9	249	;
    Serv2HiAs4H	equ		0x1FA	250	;
    Serv2HiAs4L	equ		0x1FB	251	;
    Serv3HiAs4H	equ		0x1FC	252	;
    Serv3HiAs4L	equ		0x1FD	253	;
    Serv4HiAs4H	equ		0x1FE	254	;
    Serv4HiAs4L	equ		0x1FF	255	;
*/