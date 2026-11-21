// Frozen vectors generated from Python reference f1d0e3c during native migration.
// Python reference SHA-256: f4afc0887025b29c128cfeb79237c22e59196a3d35a1bc1ab0a30afad2d63a07
package org.openwrench.nativecore

internal object ObdReferenceFixtures {
    val scalarPids = setOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x1F, 0x21, 0x22, 0x23, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x3C, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x50, 0x52, 0x53, 0x59, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x61, 0x62, 0x63, 0x8D, 0x8E, 0xA4, 0xA5, 0xA6)
    val snapshotPids = setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x21, 0x23, 0x24, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x3C, 0x41, 0x42, 0x43, 0x44, 0x47, 0x49, 0x4A, 0x4F, 0x51, 0x55, 0x56, 0x66, 0x67, 0x68)
    val rangeBases = setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)
    val metadata = """01	Monitor status since DTC clear	-	true	4
03	Fuel system status	-	true	2
04	Calculated engine load	%	false	1
05	Engine coolant temperature	°C	false	1
06	Short-term fuel trim, Bank 1	%	false	1
07	Long-term fuel trim, Bank 1	%	false	1
08	Short-term fuel trim, Bank 2	%	false	1
09	Long-term fuel trim, Bank 2	%	false	1
0A	Fuel pressure	kPa	false	1
0B	Intake manifold absolute pressure	kPa	false	1
0C	Engine speed	rpm	false	2
0D	Vehicle speed	km/h	false	1
0E	Timing advance	°	false	1
0F	Intake air temperature	°C	false	1
10	Mass air flow	g/s	false	2
11	Absolute throttle position	%	false	1
13	Oxygen sensor presence	-	true	1
15	Narrow-range oxygen sensor 1	-	true	2
1C	OBD standard	-	true	1
1F	Engine runtime since start	s	false	2
21	Distance traveled with MIL on	km	false	2
22	Fuel rail pressure relative to manifold vacuum	kPa	false	2
23	Fuel rail gauge pressure	kPa	false	2
24	Wide-range oxygen sensor 1	-	true	4
2E	Commanded evaporative purge	%	false	1
2F	Fuel tank level	%	false	1
30	Warm-ups since DTCs cleared	count	false	1
31	Distance since DTCs cleared	km	false	2
32	Evaporative system vapor pressure	Pa	false	2
33	Barometric pressure	kPa	false	1
3C	Catalyst temperature	°C	false	2
41	Monitor status this drive cycle	-	true	4
42	Control module voltage	V	false	2
43	Absolute load	%	false	2
44	Commanded equivalence ratio	ratio	false	2
45	Relative throttle position	%	false	1
46	Ambient air temperature	°C	false	1
47	Absolute throttle position B	%	false	1
48	Absolute throttle position C	%	false	1
49	Accelerator pedal position D	%	false	1
4A	Accelerator pedal position E	%	false	1
4B	Accelerator pedal position F	%	false	1
4C	Commanded throttle actuator	%	false	1
4D	Time run with MIL on	min	false	2
4E	Time since DTCs cleared	min	false	2
4F	Maximum sensor values	-	true	4
50	Maximum mass-airflow rate	g/s	false	1
51	Fuel type	-	true	1
52	Ethanol fuel percentage	%	false	1
53	Absolute evaporative-system vapor pressure	kPa	false	2
55	Secondary oxygen sensor fuel trim, banks 1 and 3	%	true	2
56	Secondary oxygen sensor fuel trim, banks 1 and 3	%	true	2
59	Fuel rail absolute pressure	kPa	false	2
5A	Relative accelerator pedal position	%	false	1
5B	Hybrid battery remaining life	%	false	1
5C	Engine oil temperature	°C	false	1
5D	Fuel injection timing	°	false	2
5E	Engine fuel rate	L/h	false	2
61	Driver-demanded engine torque	%	false	1
62	Actual engine torque	%	false	1
63	Engine reference torque	N·m	false	2
66	Mass airflow sensors A and B	-	true	5
67	Engine coolant temperature sensors 1 and 2	°C	true	3
68	Intake air temperature sensors 1 and 2	°C	true	3
8D	Throttle position G	%	false	1
8E	Engine friction torque	%	false	1
A4	Transmission actual gear ratio	ratio	false	2
A5	Commanded diesel exhaust-fluid dosing	%	false	1
A6	Odometer	km	false	4"""
    val bitmaps = """00	410000000000	-
00	410000000001	20
00	410080000000	01
00	4100FFFFFFFF	01,02,03,04,05,06,07,08,09,0A,0B,0C,0D,0E,0F,10,11,12,13,14,15,16,17,18,19,1A,1B,1C,1D,1E,1F,20
00	4100081B0001	05,0C,0D,0F,10,20
00	4100A5A55A5A	01,03,06,08,09,0B,0E,10,12,14,15,17,1A,1C,1D,1F
20	412000000000	-
20	412000000001	40
20	412080000000	21
20	4120FFFFFFFF	21,22,23,24,25,26,27,28,29,2A,2B,2C,2D,2E,2F,30,31,32,33,34,35,36,37,38,39,3A,3B,3C,3D,3E,3F,40
20	4120081B0001	25,2C,2D,2F,30,40
20	4120A5A55A5A	21,23,26,28,29,2B,2E,30,32,34,35,37,3A,3C,3D,3F
40	414000000000	-
40	414000000001	60
40	414080000000	41
40	4140FFFFFFFF	41,42,43,44,45,46,47,48,49,4A,4B,4C,4D,4E,4F,50,51,52,53,54,55,56,57,58,59,5A,5B,5C,5D,5E,5F,60
40	4140081B0001	45,4C,4D,4F,50,60
40	4140A5A55A5A	41,43,46,48,49,4B,4E,50,52,54,55,57,5A,5C,5D,5F
60	416000000000	-
60	416000000001	80
60	416080000000	61
60	4160FFFFFFFF	61,62,63,64,65,66,67,68,69,6A,6B,6C,6D,6E,6F,70,71,72,73,74,75,76,77,78,79,7A,7B,7C,7D,7E,7F,80
60	4160081B0001	65,6C,6D,6F,70,80
60	4160A5A55A5A	61,63,66,68,69,6B,6E,70,72,74,75,77,7A,7C,7D,7F
80	418000000000	-
80	418000000001	A0
80	418080000000	81
80	4180FFFFFFFF	81,82,83,84,85,86,87,88,89,8A,8B,8C,8D,8E,8F,90,91,92,93,94,95,96,97,98,99,9A,9B,9C,9D,9E,9F,A0
80	4180081B0001	85,8C,8D,8F,90,A0
80	4180A5A55A5A	81,83,86,88,89,8B,8E,90,92,94,95,97,9A,9C,9D,9F
A0	41A000000000	-
A0	41A000000001	C0
A0	41A080000000	A1
A0	41A0FFFFFFFF	A1,A2,A3,A4,A5,A6,A7,A8,A9,AA,AB,AC,AD,AE,AF,B0,B1,B2,B3,B4,B5,B6,B7,B8,B9,BA,BB,BC,BD,BE,BF,C0
A0	41A0081B0001	A5,AC,AD,AF,B0,C0
A0	41A0A5A55A5A	A1,A3,A6,A8,A9,AB,AE,B0,B2,B4,B5,B7,BA,BC,BD,BF
C0	41C000000000	-
C0	41C000000001	E0
C0	41C080000000	C1
C0	41C0FFFFFFFF	C1,C2,C3,C4,C5,C6,C7,C8,C9,CA,CB,CC,CD,CE,CF,D0,D1,D2,D3,D4,D5,D6,D7,D8,D9,DA,DB,DC,DD,DE,DF,E0
C0	41C0081B0001	C5,CC,CD,CF,D0,E0
C0	41C0A5A55A5A	C1,C3,C6,C8,C9,CB,CE,D0,D2,D4,D5,D7,DA,DC,DD,DF
E0	41E000000000	-
E0	41E000000001	-
E0	41E080000000	E1
E0	41E0FFFFFFFF	E1,E2,E3,E4,E5,E6,E7,E8,E9,EA,EB,EC,ED,EE,EF,F0,F1,F2,F3,F4,F5,F6,F7,F8,F9,FA,FB,FC,FD,FE,FF
E0	41E0081B0001	E5,EC,ED,EF,F0
E0	41E0A5A55A5A	E1,E3,E6,E8,E9,EB,EE,F0,F2,F4,F5,F7,FA,FC,FD,FF"""
    val groups: List<String> = listOf(
        """01	410100000000	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=false|monitors.8.complete=unavailable|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=false|monitors.10.complete=unavailable
01	410101010101	F	mil_on=false|dtc_count=1|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=false|monitors.8.complete=unavailable|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=false|monitors.10.complete=unavailable
01	41017F7F7F7F	F	mil_on=false|dtc_count=127|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=EGR/VVT system|monitors.8.available=false|monitors.8.complete=unavailable
01	410180808080	F	mil_on=true|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=false|monitors.8.complete=unavailable|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=false
01	4101FFFFFFFF	F	mil_on=true|dtc_count=127|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=false
01	41011EE3A741	F	mil_on=false|dtc_count=30|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=heated catalyst|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=true|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=true
01	41014B6FFA68	F	mil_on=false|dtc_count=75|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=NMHC catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
01	41013B565B3F	F	mil_on=false|dtc_count=59|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=heated catalyst|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=evaporative system|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=secondary air|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=gasoline particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=oxygen sensor|monitors.8.available=false|monitors.8.complete=unavailable|monitors.9.name=oxygen sensor heater|monitors.9.available=true|monitors.9.complete=true|monitors.10.name=EGR/VVT system|monitors.10.available=false|monitors.10.complete=unavailable
01	410173601C07	F	mil_on=false|dtc_count=115|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=secondary air|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=gasoline particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=oxygen sensor|monitors.8.available=false|monitors.8.complete=unavailable|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=false|monitors.10.complete=unavailable
01	4101503C6FEB	F	mil_on=false|dtc_count=80|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=EGR/VVT system|monitors.8.available=false|monitors.8.complete=unavailable
01	41018F110BC3	F	mil_on=true|dtc_count=15|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=heated catalyst|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=evaporative system|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=secondary air|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=false|monitors.8.complete=unavailable|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=false|monitors.10.complete=unavailable
01	410165D3650A	F	mil_on=false|dtc_count=101|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=true|monitors.9.name=oxygen sensor heater|monitors.9.available=true|monitors.9.complete=true|monitors.10.name=EGR/VVT system|monitors.10.available=false|monitors.10.complete=unavailable
01	4101C7752056	F	mil_on=true|dtc_count=71|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=true|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=false|monitors.10.complete=unavailable
01	4101EC191DCA	F	mil_on=true|dtc_count=108|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=false|monitors.8.complete=unavailable
01	41010000FF00	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=heated catalyst|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=secondary air|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=gasoline particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=true|monitors.9.name=oxygen sensor heater|monitors.9.available=true|monitors.9.complete=true|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=true
01	41010000FFFF	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=heated catalyst|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=secondary air|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=gasoline particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=false|monitors.9.name=oxygen sensor heater|monitors.9.available=true|monitors.9.complete=false|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=false
01	41010000A55A	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=true|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=true
01	410100070000	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=false|monitors.8.complete=unavailable|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=false|monitors.10.complete=unavailable
01	41010007FF00	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=heated catalyst|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=secondary air|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=gasoline particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=true|monitors.9.name=oxygen sensor heater|monitors.9.available=true|monitors.9.complete=true|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=true
01	41010007FFFF	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=heated catalyst|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=secondary air|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=gasoline particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=false|monitors.9.name=oxygen sensor heater|monitors.9.available=true|monitors.9.complete=false|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=false
01	41010007A55A	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=true|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=true
01	410100080000	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=NMHC catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=false|monitors.8.complete=unavailable
01	41010008FF00	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
01	41010008FFFF	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=false
01	41010008A55A	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
01	4101000F0000	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=NMHC catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=false|monitors.8.complete=unavailable
01	4101000FFF00	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
01	4101000FFFFF	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=false
01	4101000FA55A	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
01	4101007F0000	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=false|monitors.8.complete=unavailable
01	4101007FFF00	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
01	4101007FFFFF	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=false
01	4101007FA55A	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
01	410100FF0000	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=false|monitors.8.complete=unavailable
01	410100FFFF00	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
01	410100FFFFFF	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=false
01	410100FFA55A	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true""",
        """03	41030000	F	system_1=engine off|system_2=engine off
03	41030101	F	system_1=open loop: insufficient engine temperature|system_2=open loop: insufficient engine temperature
03	41037F7F	E
03	41038080	E
03	4103FFFF	E
03	41036C5F	E
03	4103E59B	E
03	410363A4	E
03	4103AC36	E
03	410344F3	E
03	4103C6EE	E
03	410371F4	E
03	410384C6	E
03	41036443	E
03	41030001	F	system_1=engine off|system_2=open loop: insufficient engine temperature
03	41030002	F	system_1=engine off|system_2=closed loop: oxygen-sensor feedback
03	41030004	F	system_1=engine off|system_2=open loop: load or deceleration fuel cut
03	41030008	F	system_1=engine off|system_2=open loop: system fault
03	41030010	F	system_1=engine off|system_2=closed loop: oxygen-sensor feedback fault
03	41030100	F	system_1=open loop: insufficient engine temperature|system_2=engine off
03	41030102	F	system_1=open loop: insufficient engine temperature|system_2=closed loop: oxygen-sensor feedback
03	41030104	F	system_1=open loop: insufficient engine temperature|system_2=open loop: load or deceleration fuel cut
03	41030108	F	system_1=open loop: insufficient engine temperature|system_2=open loop: system fault
03	41030110	F	system_1=open loop: insufficient engine temperature|system_2=closed loop: oxygen-sensor feedback fault
03	41030200	F	system_1=closed loop: oxygen-sensor feedback|system_2=engine off
03	41030201	F	system_1=closed loop: oxygen-sensor feedback|system_2=open loop: insufficient engine temperature
03	41030202	F	system_1=closed loop: oxygen-sensor feedback|system_2=closed loop: oxygen-sensor feedback
03	41030204	F	system_1=closed loop: oxygen-sensor feedback|system_2=open loop: load or deceleration fuel cut
03	41030208	F	system_1=closed loop: oxygen-sensor feedback|system_2=open loop: system fault
03	41030210	F	system_1=closed loop: oxygen-sensor feedback|system_2=closed loop: oxygen-sensor feedback fault
03	41030400	F	system_1=open loop: load or deceleration fuel cut|system_2=engine off
03	41030401	F	system_1=open loop: load or deceleration fuel cut|system_2=open loop: insufficient engine temperature
03	41030402	F	system_1=open loop: load or deceleration fuel cut|system_2=closed loop: oxygen-sensor feedback
03	41030404	F	system_1=open loop: load or deceleration fuel cut|system_2=open loop: load or deceleration fuel cut
03	41030408	F	system_1=open loop: load or deceleration fuel cut|system_2=open loop: system fault
03	41030410	F	system_1=open loop: load or deceleration fuel cut|system_2=closed loop: oxygen-sensor feedback fault
03	41030800	F	system_1=open loop: system fault|system_2=engine off
03	41030801	F	system_1=open loop: system fault|system_2=open loop: insufficient engine temperature
03	41030802	F	system_1=open loop: system fault|system_2=closed loop: oxygen-sensor feedback
03	41030804	F	system_1=open loop: system fault|system_2=open loop: load or deceleration fuel cut
03	41030808	F	system_1=open loop: system fault|system_2=open loop: system fault
03	41030810	F	system_1=open loop: system fault|system_2=closed loop: oxygen-sensor feedback fault
03	41031000	F	system_1=closed loop: oxygen-sensor feedback fault|system_2=engine off
03	41031001	F	system_1=closed loop: oxygen-sensor feedback fault|system_2=open loop: insufficient engine temperature
03	41031002	F	system_1=closed loop: oxygen-sensor feedback fault|system_2=closed loop: oxygen-sensor feedback
03	41031004	F	system_1=closed loop: oxygen-sensor feedback fault|system_2=open loop: load or deceleration fuel cut
03	41031008	F	system_1=closed loop: oxygen-sensor feedback fault|system_2=open loop: system fault
03	41031010	F	system_1=closed loop: oxygen-sensor feedback fault|system_2=closed loop: oxygen-sensor feedback fault""",
        """04	410400	S	0.0
04	410401	S	0.39215686274509803
04	41047F	S	49.80392156862745
04	410480	S	50.19607843137255
04	4104FF	S	100.0
04	4104CE	S	80.7843137254902
04	4104F2	S	94.90196078431373
04	410438	S	21.96078431372549
04	4104FD	S	99.2156862745098
04	4104F9	S	97.6470588235294
04	410417	S	9.019607843137255
04	410403	S	1.1764705882352942
04	410482	S	50.98039215686274""",
        """05	410500	S	-40.0
05	410501	S	-39.0
05	41057F	S	87.0
05	410580	S	88.0
05	4105FF	S	215.0
05	410543	S	27.0
05	4105C3	S	155.0
05	410533	S	11.0
05	41057C	S	84.0
05	410566	S	62.0
05	410550	S	40.0
05	41050A	S	-30.0
05	410527	S	-1.0
05	4105CB	S	163.0""",
        """06	410600	S	-100.0
06	410601	S	-99.21875
06	41067F	S	-0.78125
06	410680	S	0.0
06	4106FF	S	99.21875
06	4106BC	S	46.875
06	4106C9	S	57.03125
06	41069E	S	23.4375
06	41064C	S	-40.625
06	41067A	S	-4.6875
06	41061E	S	-76.5625
06	41062A	S	-67.1875
06	41068F	S	11.71875
06	410651	S	-36.71875""",
        """07	410700	S	-100.0
07	410701	S	-99.21875
07	41077F	S	-0.78125
07	410780	S	0.0
07	4107FF	S	99.21875
07	410729	S	-67.96875
07	410721	S	-74.21875
07	41074A	S	-42.1875
07	410781	S	0.78125
07	41078E	S	10.9375
07	41071A	S	-79.6875
07	410715	S	-83.59375
07	410711	S	-86.71875
07	41073A	S	-54.6875""",
        """08	410800	S	-100.0
08	410801	S	-99.21875
08	41087F	S	-0.78125
08	410880	S	0.0
08	4108FF	S	99.21875
08	410869	S	-17.96875
08	4108B9	S	44.53125
08	410875	S	-8.59375
08	410834	S	-59.375
08	410896	S	17.1875
08	410821	S	-74.21875
08	410818	S	-81.25
08	41086C	S	-15.625""",
        """09	410900	S	-100.0
09	410901	S	-99.21875
09	41097F	S	-0.78125
09	410980	S	0.0
09	4109FF	S	99.21875
09	410999	S	19.53125
09	410942	S	-48.4375
09	410982	S	1.5625
09	410986	S	4.6875
09	410917	S	-82.03125
09	410904	S	-96.875
09	4109F4	S	90.625
09	4109D3	S	64.84375
09	41098E	S	10.9375""",
        """0A	410A00	S	0.0
0A	410A01	S	3.0
0A	410A7F	S	381.0
0A	410A80	S	384.0
0A	410AFF	S	765.0
0A	410AAD	S	519.0
0A	410A04	S	12.0
0A	410AA3	S	489.0
0A	410A75	S	351.0
0A	410AE4	S	684.0
0A	410ABD	S	567.0
0A	410AC3	S	585.0
0A	410A0E	S	42.0""",
        """0B	410B00	S	0.0
0B	410B01	S	1.0
0B	410B7F	S	127.0
0B	410B80	S	128.0
0B	410BFF	S	255.0
0B	410BD2	S	210.0
0B	410B4C	S	76.0
0B	410BB5	S	181.0
0B	410B89	S	137.0
0B	410B36	S	54.0
0B	410B76	S	118.0
0B	410B19	S	25.0
0B	410BBE	S	190.0
0B	410B2C	S	44.0""",
        """0C	410C0000	S	0.0
0C	410C0101	S	64.25
0C	410C7F7F	S	8159.75
0C	410C8080	S	8224.0
0C	410CFFFF	S	16383.75
0C	410CE9C2	S	14960.5
0C	410C946C	S	9499.0
0C	410CD140	S	13392.0
0C	410C0CC1	S	816.25
0C	410CFBC5	S	16113.25
0C	410C5062	S	5144.5
0C	410CE8EF	S	14907.75
0C	410C3792	S	3556.5
0C	410C21E9	S	2170.25""",
        """0D	410D00	S	0.0
0D	410D01	S	1.0
0D	410D7F	S	127.0
0D	410D80	S	128.0
0D	410DFF	S	255.0
0D	410D32	S	50.0
0D	410DE8	S	232.0
0D	410D5A	S	90.0
0D	410D56	S	86.0
0D	410D57	S	87.0
0D	410D58	S	88.0
0D	410D48	S	72.0
0D	410D94	S	148.0""",
        """0E	410E00	S	-64.0
0E	410E01	S	-63.5
0E	410E7F	S	-0.5
0E	410E80	S	0.0
0E	410EFF	S	63.5
0E	410EF6	S	59.0
0E	410E0A	S	-59.0
0E	410EDC	S	46.0
0E	410E06	S	-61.0
0E	410E38	S	-36.0
0E	410E1E	S	-49.0
0E	410E6D	S	-9.5
0E	410E70	S	-8.0
0E	410ED1	S	40.5""",
        """0F	410F00	S	-40.0
0F	410F01	S	-39.0
0F	410F7F	S	87.0
0F	410F80	S	88.0
0F	410FFF	S	215.0
0F	410F0A	S	-30.0
0F	410F54	S	44.0
0F	410FDA	S	178.0
0F	410FA5	S	125.0
0F	410F9F	S	119.0
0F	410FD9	S	177.0
0F	410F50	S	40.0
0F	410FED	S	197.0
0F	410F5E	S	54.0""",
        """10	41100000	S	0.0
10	41100101	S	2.57
10	41107F7F	S	326.39
10	41108080	S	328.96
10	4110FFFF	S	655.35
10	4110AF61	S	448.97
10	411009B9	S	24.89
10	4110DAAF	S	559.83
10	411037A9	S	142.49
10	4110AC79	S	441.53
10	4110EA17	S	599.27
10	4110C43A	S	502.34
10	411063AD	S	255.17
10	4110FF54	S	653.64""",
        """11	411100	S	0.0
11	411101	S	0.39215686274509803
11	41117F	S	49.80392156862745
11	411180	S	50.19607843137255
11	4111FF	S	100.0
11	41110F	S	5.882352941176471
11	411147	S	27.84313725490196
11	4111AB	S	67.05882352941177
11	4111E5	S	89.80392156862744
11	411145	S	27.058823529411764
11	41118F	S	56.07843137254902
11	4111FE	S	99.6078431372549
11	41119C	S	61.1764705882353""",
        """13	411300	F	bank_1.0=false|bank_1.1=false|bank_1.2=false|bank_1.3=false|bank_2.0=false|bank_2.1=false|bank_2.2=false|bank_2.3=false
13	411301	F	bank_1.0=true|bank_1.1=false|bank_1.2=false|bank_1.3=false|bank_2.0=false|bank_2.1=false|bank_2.2=false|bank_2.3=false
13	41137F	F	bank_1.0=true|bank_1.1=true|bank_1.2=true|bank_1.3=true|bank_2.0=true|bank_2.1=true|bank_2.2=true|bank_2.3=false
13	411380	F	bank_1.0=false|bank_1.1=false|bank_1.2=false|bank_1.3=false|bank_2.0=false|bank_2.1=false|bank_2.2=false|bank_2.3=true
13	4113FF	F	bank_1.0=true|bank_1.1=true|bank_1.2=true|bank_1.3=true|bank_2.0=true|bank_2.1=true|bank_2.2=true|bank_2.3=true
13	41131D	F	bank_1.0=true|bank_1.1=false|bank_1.2=true|bank_1.3=true|bank_2.0=true|bank_2.1=false|bank_2.2=false|bank_2.3=false
13	4113F5	F	bank_1.0=true|bank_1.1=false|bank_1.2=true|bank_1.3=false|bank_2.0=true|bank_2.1=true|bank_2.2=true|bank_2.3=true
13	411305	F	bank_1.0=true|bank_1.1=false|bank_1.2=true|bank_1.3=false|bank_2.0=false|bank_2.1=false|bank_2.2=false|bank_2.3=false
13	4113B1	F	bank_1.0=true|bank_1.1=false|bank_1.2=false|bank_1.3=false|bank_2.0=true|bank_2.1=true|bank_2.2=false|bank_2.3=true
13	411354	F	bank_1.0=false|bank_1.1=false|bank_1.2=true|bank_1.3=false|bank_2.0=true|bank_2.1=false|bank_2.2=true|bank_2.3=false
13	411339	F	bank_1.0=true|bank_1.1=false|bank_1.2=false|bank_1.3=true|bank_2.0=true|bank_2.1=true|bank_2.2=false|bank_2.3=false
13	411308	F	bank_1.0=false|bank_1.1=false|bank_1.2=false|bank_1.3=true|bank_2.0=false|bank_2.1=false|bank_2.2=false|bank_2.3=false
13	4113D9	F	bank_1.0=true|bank_1.1=false|bank_1.2=false|bank_1.3=true|bank_2.0=true|bank_2.1=false|bank_2.2=true|bank_2.3=true
13	4113B7	F	bank_1.0=true|bank_1.1=true|bank_1.2=true|bank_1.3=false|bank_2.0=true|bank_2.1=true|bank_2.2=false|bank_2.3=true""",
        """15	41150000	F	voltage_volts=0.0|short_term_fuel_trim_percent=-100.0
15	41150101	F	voltage_volts=0.005|short_term_fuel_trim_percent=-99.21875
15	41157F7F	F	voltage_volts=0.635|short_term_fuel_trim_percent=-0.78125
15	41158080	F	voltage_volts=0.64|short_term_fuel_trim_percent=0.0
15	4115FFFF	F	voltage_volts=1.275|short_term_fuel_trim_percent=unavailable
15	4115AB27	F	voltage_volts=0.855|short_term_fuel_trim_percent=-69.53125
15	4115187E	F	voltage_volts=0.12|short_term_fuel_trim_percent=-1.5625
15	4115E224	F	voltage_volts=1.13|short_term_fuel_trim_percent=-71.875
15	411514B9	F	voltage_volts=0.1|short_term_fuel_trim_percent=44.53125
15	4115ABD7	F	voltage_volts=0.855|short_term_fuel_trim_percent=67.96875
15	41155D30	F	voltage_volts=0.465|short_term_fuel_trim_percent=-62.5
15	41155CE1	F	voltage_volts=0.46|short_term_fuel_trim_percent=75.78125
15	4115A6D6	F	voltage_volts=0.83|short_term_fuel_trim_percent=67.1875
15	4115BA06	F	voltage_volts=0.93|short_term_fuel_trim_percent=-95.3125""",
        """1C	411C00	F	pid=28|code=0|meaning=unavailable
1C	411C01	F	pid=28|code=1|meaning=OBD-II (CARB)
1C	411C7F	F	pid=28|code=127|meaning=reserved
1C	411C80	F	pid=28|code=128|meaning=reserved
1C	411CFF	F	pid=28|code=255|meaning=not available for assignment (SAE J1939 special meaning)
1C	411CAD	F	pid=28|code=173|meaning=reserved
1C	411C44	F	pid=28|code=68|meaning=reserved
1C	411C4D	F	pid=28|code=77|meaning=reserved
1C	411C66	F	pid=28|code=102|meaning=reserved
1C	411C38	F	pid=28|code=56|meaning=reserved
1C	411C26	F	pid=28|code=38|meaning=unavailable
1C	411CE5	F	pid=28|code=229|meaning=reserved
1C	411CD4	F	pid=28|code=212|meaning=reserved
1C	411C02	F	pid=28|code=2|meaning=OBD (EPA)
1C	411C03	F	pid=28|code=3|meaning=OBD and OBD-II
1C	411C04	F	pid=28|code=4|meaning=OBD-I
1C	411C05	F	pid=28|code=5|meaning=not OBD compliant
1C	411C06	F	pid=28|code=6|meaning=EOBD
1C	411C07	F	pid=28|code=7|meaning=EOBD and OBD-II
1C	411C08	F	pid=28|code=8|meaning=EOBD and OBD
1C	411C09	F	pid=28|code=9|meaning=EOBD, OBD, and OBD-II
1C	411C0A	F	pid=28|code=10|meaning=JOBD
1C	411C0B	F	pid=28|code=11|meaning=JOBD and OBD-II
1C	411C0C	F	pid=28|code=12|meaning=JOBD and EOBD
1C	411C0D	F	pid=28|code=13|meaning=JOBD, EOBD, and OBD-II
1C	411C0E	F	pid=28|code=14|meaning=OBD, EOBD, and KOBD
1C	411C0F	F	pid=28|code=15|meaning=OBD, OBD-II, EOBD, and KOBD
1C	411C10	F	pid=28|code=16|meaning=reserved
1C	411C11	F	pid=28|code=17|meaning=Engine Manufacturer Diagnostics (EMD)
1C	411C12	F	pid=28|code=18|meaning=Engine Manufacturer Diagnostics Enhanced (EMD+)
1C	411C13	F	pid=28|code=19|meaning=HD OBD-C
1C	411C14	F	pid=28|code=20|meaning=HD OBD
1C	411C15	F	pid=28|code=21|meaning=World Wide Harmonized OBD (WWH OBD)
1C	411C16	F	pid=28|code=22|meaning=reserved
1C	411C17	F	pid=28|code=23|meaning=HD EOBD-I without NOx control
1C	411C18	F	pid=28|code=24|meaning=HD EOBD-I with NOx control
1C	411C19	F	pid=28|code=25|meaning=HD EOBD-II without NOx control
1C	411C1A	F	pid=28|code=26|meaning=HD EOBD-II with NOx control
1C	411C1B	F	pid=28|code=27|meaning=HD ZEV
1C	411C1C	F	pid=28|code=28|meaning=Brazil OBD Phase 1 (OBDBr-1)
1C	411C1D	F	pid=28|code=29|meaning=Brazil OBD Phase 2 (OBDBr-2)
1C	411C1E	F	pid=28|code=30|meaning=Korean OBD (KOBD)
1C	411C1F	F	pid=28|code=31|meaning=India OBD I (IOBD I)
1C	411C20	F	pid=28|code=32|meaning=India OBD II (IOBD II)
1C	411C21	F	pid=28|code=33|meaning=HD EOBD Stage VI
1C	411C22	F	pid=28|code=34|meaning=OBD, OBD-II, and HD OBD
1C	411C23	F	pid=28|code=35|meaning=Brazil OBD Phase 3 (OBDBr-3)
1C	411C24	F	pid=28|code=36|meaning=unavailable
1C	411C25	F	pid=28|code=37|meaning=unavailable
1C	411C27	F	pid=28|code=39|meaning=unavailable
1C	411C28	F	pid=28|code=40|meaning=unavailable
1C	411C29	F	pid=28|code=41|meaning=unavailable
1C	411C2A	F	pid=28|code=42|meaning=unavailable
1C	411C2B	F	pid=28|code=43|meaning=unavailable
1C	411C2C	F	pid=28|code=44|meaning=unavailable
1C	411C2D	F	pid=28|code=45|meaning=unavailable
1C	411C2E	F	pid=28|code=46|meaning=unavailable
1C	411C2F	F	pid=28|code=47|meaning=unavailable
1C	411C30	F	pid=28|code=48|meaning=unavailable
1C	411C31	F	pid=28|code=49|meaning=unavailable
1C	411C32	F	pid=28|code=50|meaning=unavailable
1C	411C33	F	pid=28|code=51|meaning=unavailable
1C	411C34	F	pid=28|code=52|meaning=unavailable
1C	411C35	F	pid=28|code=53|meaning=reserved
1C	411C36	F	pid=28|code=54|meaning=reserved
1C	411C37	F	pid=28|code=55|meaning=reserved
1C	411C39	F	pid=28|code=57|meaning=reserved
1C	411C3A	F	pid=28|code=58|meaning=reserved
1C	411C3B	F	pid=28|code=59|meaning=reserved
1C	411C3C	F	pid=28|code=60|meaning=reserved
1C	411C3D	F	pid=28|code=61|meaning=reserved
1C	411C3E	F	pid=28|code=62|meaning=reserved
1C	411C3F	F	pid=28|code=63|meaning=reserved
1C	411C40	F	pid=28|code=64|meaning=reserved
1C	411C41	F	pid=28|code=65|meaning=reserved
1C	411C42	F	pid=28|code=66|meaning=reserved
1C	411C43	F	pid=28|code=67|meaning=reserved
1C	411C45	F	pid=28|code=69|meaning=reserved
1C	411C46	F	pid=28|code=70|meaning=reserved
1C	411C47	F	pid=28|code=71|meaning=reserved
1C	411C48	F	pid=28|code=72|meaning=reserved
1C	411C49	F	pid=28|code=73|meaning=reserved
1C	411C4A	F	pid=28|code=74|meaning=reserved
1C	411C4B	F	pid=28|code=75|meaning=reserved
1C	411C4C	F	pid=28|code=76|meaning=reserved
1C	411C4E	F	pid=28|code=78|meaning=reserved
1C	411C4F	F	pid=28|code=79|meaning=reserved
1C	411C50	F	pid=28|code=80|meaning=reserved
1C	411C51	F	pid=28|code=81|meaning=reserved
1C	411C52	F	pid=28|code=82|meaning=reserved
1C	411C53	F	pid=28|code=83|meaning=reserved
1C	411C54	F	pid=28|code=84|meaning=reserved
1C	411C55	F	pid=28|code=85|meaning=reserved
1C	411C56	F	pid=28|code=86|meaning=reserved
1C	411C57	F	pid=28|code=87|meaning=reserved
1C	411C58	F	pid=28|code=88|meaning=reserved
1C	411C59	F	pid=28|code=89|meaning=reserved
1C	411C5A	F	pid=28|code=90|meaning=reserved
1C	411C5B	F	pid=28|code=91|meaning=reserved
1C	411C5C	F	pid=28|code=92|meaning=reserved
1C	411C5D	F	pid=28|code=93|meaning=reserved
1C	411C5E	F	pid=28|code=94|meaning=reserved
1C	411C5F	F	pid=28|code=95|meaning=reserved
1C	411C60	F	pid=28|code=96|meaning=reserved
1C	411C61	F	pid=28|code=97|meaning=reserved
1C	411C62	F	pid=28|code=98|meaning=reserved
1C	411C63	F	pid=28|code=99|meaning=reserved
1C	411C64	F	pid=28|code=100|meaning=reserved
1C	411C65	F	pid=28|code=101|meaning=reserved
1C	411C67	F	pid=28|code=103|meaning=reserved
1C	411C68	F	pid=28|code=104|meaning=reserved
1C	411C69	F	pid=28|code=105|meaning=reserved
1C	411C6A	F	pid=28|code=106|meaning=reserved
1C	411C6B	F	pid=28|code=107|meaning=reserved
1C	411C6C	F	pid=28|code=108|meaning=reserved
1C	411C6D	F	pid=28|code=109|meaning=reserved
1C	411C6E	F	pid=28|code=110|meaning=reserved
1C	411C6F	F	pid=28|code=111|meaning=reserved
1C	411C70	F	pid=28|code=112|meaning=reserved
1C	411C71	F	pid=28|code=113|meaning=reserved
1C	411C72	F	pid=28|code=114|meaning=reserved
1C	411C73	F	pid=28|code=115|meaning=reserved
1C	411C74	F	pid=28|code=116|meaning=reserved
1C	411C75	F	pid=28|code=117|meaning=reserved
1C	411C76	F	pid=28|code=118|meaning=reserved
1C	411C77	F	pid=28|code=119|meaning=reserved
1C	411C78	F	pid=28|code=120|meaning=reserved
1C	411C79	F	pid=28|code=121|meaning=reserved
1C	411C7A	F	pid=28|code=122|meaning=reserved
1C	411C7B	F	pid=28|code=123|meaning=reserved
1C	411C7C	F	pid=28|code=124|meaning=reserved
1C	411C7D	F	pid=28|code=125|meaning=reserved
1C	411C7E	F	pid=28|code=126|meaning=reserved
1C	411C81	F	pid=28|code=129|meaning=reserved
1C	411C82	F	pid=28|code=130|meaning=reserved
1C	411C83	F	pid=28|code=131|meaning=reserved
1C	411C84	F	pid=28|code=132|meaning=reserved
1C	411C85	F	pid=28|code=133|meaning=reserved
1C	411C86	F	pid=28|code=134|meaning=reserved
1C	411C87	F	pid=28|code=135|meaning=reserved
1C	411C88	F	pid=28|code=136|meaning=reserved
1C	411C89	F	pid=28|code=137|meaning=reserved
1C	411C8A	F	pid=28|code=138|meaning=reserved
1C	411C8B	F	pid=28|code=139|meaning=reserved
1C	411C8C	F	pid=28|code=140|meaning=reserved
1C	411C8D	F	pid=28|code=141|meaning=reserved
1C	411C8E	F	pid=28|code=142|meaning=reserved
1C	411C8F	F	pid=28|code=143|meaning=reserved
1C	411C90	F	pid=28|code=144|meaning=reserved
1C	411C91	F	pid=28|code=145|meaning=reserved
1C	411C92	F	pid=28|code=146|meaning=reserved
1C	411C93	F	pid=28|code=147|meaning=reserved
1C	411C94	F	pid=28|code=148|meaning=reserved
1C	411C95	F	pid=28|code=149|meaning=reserved
1C	411C96	F	pid=28|code=150|meaning=reserved
1C	411C97	F	pid=28|code=151|meaning=reserved
1C	411C98	F	pid=28|code=152|meaning=reserved
1C	411C99	F	pid=28|code=153|meaning=reserved
1C	411C9A	F	pid=28|code=154|meaning=reserved
1C	411C9B	F	pid=28|code=155|meaning=reserved
1C	411C9C	F	pid=28|code=156|meaning=reserved
1C	411C9D	F	pid=28|code=157|meaning=reserved
1C	411C9E	F	pid=28|code=158|meaning=reserved
1C	411C9F	F	pid=28|code=159|meaning=reserved
1C	411CA0	F	pid=28|code=160|meaning=reserved
1C	411CA1	F	pid=28|code=161|meaning=reserved
1C	411CA2	F	pid=28|code=162|meaning=reserved
1C	411CA3	F	pid=28|code=163|meaning=reserved
1C	411CA4	F	pid=28|code=164|meaning=reserved
1C	411CA5	F	pid=28|code=165|meaning=reserved
1C	411CA6	F	pid=28|code=166|meaning=reserved
1C	411CA7	F	pid=28|code=167|meaning=reserved
1C	411CA8	F	pid=28|code=168|meaning=reserved
1C	411CA9	F	pid=28|code=169|meaning=reserved
1C	411CAA	F	pid=28|code=170|meaning=reserved
1C	411CAB	F	pid=28|code=171|meaning=reserved
1C	411CAC	F	pid=28|code=172|meaning=reserved
1C	411CAE	F	pid=28|code=174|meaning=reserved
1C	411CAF	F	pid=28|code=175|meaning=reserved
1C	411CB0	F	pid=28|code=176|meaning=reserved
1C	411CB1	F	pid=28|code=177|meaning=reserved
1C	411CB2	F	pid=28|code=178|meaning=reserved
1C	411CB3	F	pid=28|code=179|meaning=reserved
1C	411CB4	F	pid=28|code=180|meaning=reserved
1C	411CB5	F	pid=28|code=181|meaning=reserved
1C	411CB6	F	pid=28|code=182|meaning=reserved
1C	411CB7	F	pid=28|code=183|meaning=reserved
1C	411CB8	F	pid=28|code=184|meaning=reserved
1C	411CB9	F	pid=28|code=185|meaning=reserved
1C	411CBA	F	pid=28|code=186|meaning=reserved
1C	411CBB	F	pid=28|code=187|meaning=reserved
1C	411CBC	F	pid=28|code=188|meaning=reserved
1C	411CBD	F	pid=28|code=189|meaning=reserved
1C	411CBE	F	pid=28|code=190|meaning=reserved
1C	411CBF	F	pid=28|code=191|meaning=reserved
1C	411CC0	F	pid=28|code=192|meaning=reserved
1C	411CC1	F	pid=28|code=193|meaning=reserved
1C	411CC2	F	pid=28|code=194|meaning=reserved
1C	411CC3	F	pid=28|code=195|meaning=reserved
1C	411CC4	F	pid=28|code=196|meaning=reserved
1C	411CC5	F	pid=28|code=197|meaning=reserved
1C	411CC6	F	pid=28|code=198|meaning=reserved
1C	411CC7	F	pid=28|code=199|meaning=reserved
1C	411CC8	F	pid=28|code=200|meaning=reserved
1C	411CC9	F	pid=28|code=201|meaning=reserved
1C	411CCA	F	pid=28|code=202|meaning=reserved
1C	411CCB	F	pid=28|code=203|meaning=reserved
1C	411CCC	F	pid=28|code=204|meaning=reserved
1C	411CCD	F	pid=28|code=205|meaning=reserved
1C	411CCE	F	pid=28|code=206|meaning=reserved
1C	411CCF	F	pid=28|code=207|meaning=reserved
1C	411CD0	F	pid=28|code=208|meaning=reserved
1C	411CD1	F	pid=28|code=209|meaning=reserved
1C	411CD2	F	pid=28|code=210|meaning=reserved
1C	411CD3	F	pid=28|code=211|meaning=reserved
1C	411CD5	F	pid=28|code=213|meaning=reserved
1C	411CD6	F	pid=28|code=214|meaning=reserved
1C	411CD7	F	pid=28|code=215|meaning=reserved
1C	411CD8	F	pid=28|code=216|meaning=reserved
1C	411CD9	F	pid=28|code=217|meaning=reserved
1C	411CDA	F	pid=28|code=218|meaning=reserved
1C	411CDB	F	pid=28|code=219|meaning=reserved
1C	411CDC	F	pid=28|code=220|meaning=reserved
1C	411CDD	F	pid=28|code=221|meaning=reserved
1C	411CDE	F	pid=28|code=222|meaning=reserved
1C	411CDF	F	pid=28|code=223|meaning=reserved
1C	411CE0	F	pid=28|code=224|meaning=reserved
1C	411CE1	F	pid=28|code=225|meaning=reserved
1C	411CE2	F	pid=28|code=226|meaning=reserved
1C	411CE3	F	pid=28|code=227|meaning=reserved
1C	411CE4	F	pid=28|code=228|meaning=reserved
1C	411CE6	F	pid=28|code=230|meaning=reserved
1C	411CE7	F	pid=28|code=231|meaning=reserved
1C	411CE8	F	pid=28|code=232|meaning=reserved
1C	411CE9	F	pid=28|code=233|meaning=reserved
1C	411CEA	F	pid=28|code=234|meaning=reserved
1C	411CEB	F	pid=28|code=235|meaning=reserved
1C	411CEC	F	pid=28|code=236|meaning=reserved
1C	411CED	F	pid=28|code=237|meaning=reserved
1C	411CEE	F	pid=28|code=238|meaning=reserved
1C	411CEF	F	pid=28|code=239|meaning=reserved
1C	411CF0	F	pid=28|code=240|meaning=reserved
1C	411CF1	F	pid=28|code=241|meaning=reserved
1C	411CF2	F	pid=28|code=242|meaning=reserved
1C	411CF3	F	pid=28|code=243|meaning=reserved
1C	411CF4	F	pid=28|code=244|meaning=reserved
1C	411CF5	F	pid=28|code=245|meaning=reserved
1C	411CF6	F	pid=28|code=246|meaning=reserved
1C	411CF7	F	pid=28|code=247|meaning=reserved
1C	411CF8	F	pid=28|code=248|meaning=reserved
1C	411CF9	F	pid=28|code=249|meaning=reserved
1C	411CFA	F	pid=28|code=250|meaning=reserved
1C	411CFB	F	pid=28|code=251|meaning=not available for assignment (SAE J1939 special meaning)
1C	411CFC	F	pid=28|code=252|meaning=not available for assignment (SAE J1939 special meaning)
1C	411CFD	F	pid=28|code=253|meaning=not available for assignment (SAE J1939 special meaning)
1C	411CFE	F	pid=28|code=254|meaning=not available for assignment (SAE J1939 special meaning)""",
        """1F	411F0000	I	0
1F	411F0101	I	257
1F	411F7F7F	I	32639
1F	411F8080	I	32896
1F	411FFFFF	I	65535
1F	411F43EC	I	17388
1F	411F0CBC	I	3260
1F	411FFE5C	I	65116
1F	411F63EF	I	25583
1F	411FBDB0	I	48560
1F	411FF4D4	I	62676
1F	411FFE57	I	65111
1F	411F765A	I	30298
1F	411FBD3B	I	48443""",
        """21	41210000	I	0
21	41210101	I	257
21	41217F7F	I	32639
21	41218080	I	32896
21	4121FFFF	I	65535
21	4121DA9A	I	55962
21	412134C4	I	13508
21	41211F65	I	8037
21	41215F60	I	24416
21	41212B17	I	11031
21	41213CB9	I	15545
21	4121B5AA	I	46506
21	4121F107	I	61703
21	4121C7FC	I	51196""",
        """22	41220000	S	0.0
22	41220101	S	20.303
22	41227F7F	S	2578.481
22	41228080	S	2598.784
22	4122FFFF	S	5177.265
22	41224AB1	S	1510.559
22	4122DED5	S	4506.555
22	412228C1	S	824.207
22	41224A67	S	1504.713
22	4122EACA	S	4748.374
22	412279ED	S	2465.827
22	41229DAC	S	3188.756
22	4122E583	S	4641.645
22	41226996	S	2135.37""",
        """23	41230000	S	0.0
23	41230101	S	2570.0
23	41237F7F	S	326390.0
23	41238080	S	328960.0
23	4123FFFF	S	655350.0
23	4123DD27	S	566150.0
23	4123599E	S	229420.0
23	4123C73B	S	510030.0
23	41233390	S	132000.0
23	412330C7	S	124870.0
23	4123413F	S	167030.0
23	4123F160	S	617920.0
23	41236820	S	266560.0
23	41233292	S	129460.0""",
        """24	412400000000	F	equivalence_ratio=0.0|voltage_volts=0.0
24	412401010101	F	equivalence_ratio=0.007843017578125|voltage_volts=0.0313720703125
24	41247F7F7F7F	F	equivalence_ratio=0.996063232421875|voltage_volts=3.9842529296875
24	412480808080	F	equivalence_ratio=1.00390625|voltage_volts=4.015625
24	4124FFFFFFFF	F	equivalence_ratio=1.999969482421875|voltage_volts=7.9998779296875
24	41242A43D37B	F	equivalence_ratio=0.330169677734375|voltage_volts=6.6087646484375
24	4124998DBFA9	F	equivalence_ratio=1.199615478515625|voltage_volts=5.9893798828125
24	4124306EE8BC	F	equivalence_ratio=0.37835693359375|voltage_volts=7.27294921875
24	41242283B26B	F	equivalence_ratio=0.269622802734375|voltage_volts=5.5755615234375
24	41243D38A2B9	F	equivalence_ratio=0.478271484375|voltage_volts=5.0850830078125
24	412484BBEE04	F	equivalence_ratio=1.036956787109375|voltage_volts=7.43798828125
24	4124F16AA3B0	F	equivalence_ratio=1.88604736328125|voltage_volts=5.115234375
24	4124B93656D2	F	equivalence_ratio=1.44696044921875|voltage_volts=2.713134765625
24	4124A16E97B8	F	equivalence_ratio=1.26116943359375|voltage_volts=4.7412109375""",
        """2E	412E00	S	0.0
2E	412E01	S	0.39215686274509803
2E	412E7F	S	49.80392156862745
2E	412E80	S	50.19607843137255
2E	412EFF	S	100.0
2E	412E0E	S	5.490196078431373
2E	412EB9	S	72.54901960784314
2E	412E15	S	8.235294117647058
2E	412E51	S	31.764705882352942
2E	412E23	S	13.72549019607843
2E	412E2A	S	16.470588235294116
2E	412E1E	S	11.764705882352942
2E	412E1A	S	10.196078431372548
2E	412E16	S	8.627450980392156""",
        """2F	412F00	S	0.0
2F	412F01	S	0.39215686274509803
2F	412F7F	S	49.80392156862745
2F	412F80	S	50.19607843137255
2F	412FFF	S	100.0
2F	412F72	S	44.705882352941174
2F	412F64	S	39.21568627450981
2F	412F4C	S	29.80392156862745
2F	412F18	S	9.411764705882353
2F	412FB9	S	72.54901960784314
2F	412F42	S	25.88235294117647
2F	412FBE	S	74.50980392156863
2F	412FAC	S	67.45098039215686
2F	412FE7	S	90.58823529411765""",
        """30	413000	I	0
30	413001	I	1
30	41307F	I	127
30	413080	I	128
30	4130FF	I	255
30	413061	I	97
30	413040	I	64
30	4130D3	I	211
30	41302A	I	42
30	413081	I	129
30	41308F	I	143
30	4130CC	I	204
30	413032	I	50
30	4130F4	I	244""",
        """31	41310000	I	0
31	41310101	I	257
31	41317F7F	I	32639
31	41318080	I	32896
31	4131FFFF	I	65535
31	41315E6D	I	24173
31	4131D042	I	53314
31	4131A81A	I	43034
31	41317496	I	29846
31	41315869	I	22633
31	41311608	I	5640
31	4131CEC3	I	52931
31	41316F21	I	28449
31	413165ED	I	26093""",
        """32	41320000	S	0.0
32	41320101	S	64.25
32	41327F7F	S	8159.75
32	41328080	S	-8160.0
32	4132FFFF	S	-0.25
32	413259D7	S	5749.75
32	41325ECD	S	6067.25
32	41324333	S	4300.75
32	41327DC9	S	8050.25
32	4132AEF9	S	-5185.75
32	4132A587	S	-5790.25
32	413239BB	S	3694.75
32	4132BA3C	S	-4465.0
32	41322DD0	S	2932.0""",
        """33	413300	S	0.0
33	413301	S	1.0
33	41337F	S	127.0
33	413380	S	128.0
33	4133FF	S	255.0
33	413326	S	38.0
33	41339D	S	157.0
33	413364	S	100.0
33	413339	S	57.0
33	41331A	S	26.0
33	413302	S	2.0
33	413370	S	112.0
33	413387	S	135.0""",
        """3C	413C0000	S	-40.0
3C	413C0101	S	-14.3
3C	413C7F7F	S	3223.9
3C	413C8080	S	3249.6
3C	413CFFFF	S	6513.5
3C	413CAD03	S	4389.1
3C	413C9701	S	3825.7
3C	413C80BA	S	3255.4
3C	413C1EEF	S	751.9
3C	413C4A5D	S	1863.7
3C	413C580F	S	2214.3
3C	413CE4B6	S	5815.0
3C	413CC81F	S	5083.1
3C	413CE073	S	5705.9""",
        """41	414100000000	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=false|monitors.8.complete=unavailable|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=false|monitors.10.complete=unavailable
41	414101010101	E
41	41417F7F7F7F	E
41	414180808080	E
41	4141FFFFFFFF	E
41	4141EBD39834	E
41	41415C02AFDB	E
41	414185E58CDA	E
41	4141288F4337	E
41	4141ACE9941A	E
41	4141F598E4F2	E
41	414138CFFDEC	E
41	41417C4A77CC	E
41	4141F7F6A0B7	E
41	41410000FF00	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=heated catalyst|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=secondary air|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=gasoline particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=true|monitors.9.name=oxygen sensor heater|monitors.9.available=true|monitors.9.complete=true|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=true
41	41410000FFFF	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=heated catalyst|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=secondary air|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=gasoline particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=false|monitors.9.name=oxygen sensor heater|monitors.9.available=true|monitors.9.complete=false|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=false
41	41410000A55A	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=true|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=true
41	414100070000	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=false|monitors.8.complete=unavailable|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=false|monitors.10.complete=unavailable
41	41410007FF00	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=heated catalyst|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=secondary air|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=gasoline particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=true|monitors.9.name=oxygen sensor heater|monitors.9.available=true|monitors.9.complete=true|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=true
41	41410007FFFF	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=heated catalyst|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=secondary air|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=gasoline particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=false|monitors.9.name=oxygen sensor heater|monitors.9.available=true|monitors.9.complete=false|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=false
41	41410007A55A	F	mil_on=false|dtc_count=0|ignition_type=spark|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=heated catalyst|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=evaporative system|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=secondary air|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=gasoline particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=oxygen sensor|monitors.8.available=true|monitors.8.complete=true|monitors.9.name=oxygen sensor heater|monitors.9.available=false|monitors.9.complete=unavailable|monitors.10.name=EGR/VVT system|monitors.10.available=true|monitors.10.complete=true
41	414100080000	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=NMHC catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=false|monitors.8.complete=unavailable
41	41410008FF00	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
41	41410008FFFF	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=false
41	41410008A55A	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=false|monitors.0.complete=unavailable|monitors.1.name=fuel system|monitors.1.available=false|monitors.1.complete=unavailable|monitors.2.name=misfire|monitors.2.available=false|monitors.2.complete=unavailable|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
41	4141000F0000	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=NMHC catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=false|monitors.8.complete=unavailable
41	4141000FFF00	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
41	4141000FFFFF	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=false
41	4141000FA55A	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=true|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=true|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=true|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
41	4141007F0000	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=false|monitors.8.complete=unavailable
41	4141007FFF00	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
41	4141007FFFFF	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=false
41	4141007FA55A	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
41	414100FF0000	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=false|monitors.3.complete=unavailable|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=false|monitors.6.complete=unavailable|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=false|monitors.8.complete=unavailable
41	414100FFFF00	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=true|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=true|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=true|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true
41	414100FFFFFF	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=false|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=true|monitors.4.complete=false|monitors.5.name=boost pressure|monitors.5.available=true|monitors.5.complete=false|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=false|monitors.7.name=particulate filter|monitors.7.available=true|monitors.7.complete=false|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=false
41	414100FFA55A	F	mil_on=false|dtc_count=0|ignition_type=compression|monitors.0.name=comprehensive components|monitors.0.available=true|monitors.0.complete=false|monitors.1.name=fuel system|monitors.1.available=true|monitors.1.complete=false|monitors.2.name=misfire|monitors.2.available=true|monitors.2.complete=false|monitors.3.name=NMHC catalyst|monitors.3.available=true|monitors.3.complete=true|monitors.4.name=NOx/SCR aftertreatment|monitors.4.available=false|monitors.4.complete=unavailable|monitors.5.name=boost pressure|monitors.5.available=false|monitors.5.complete=unavailable|monitors.6.name=exhaust gas sensor|monitors.6.available=true|monitors.6.complete=true|monitors.7.name=particulate filter|monitors.7.available=false|monitors.7.complete=unavailable|monitors.8.name=EGR/VVT system|monitors.8.available=true|monitors.8.complete=true""",
        """42	41420000	S	0.0
42	41420101	S	0.257
42	41427F7F	S	32.639
42	41428080	S	32.896
42	4142FFFF	S	65.535
42	41422D2C	S	11.564
42	41424F5A	S	20.314
42	41420875	S	2.165
42	41428788	S	34.696
42	4142631F	S	25.375
42	4142F8E9	S	63.721
42	4142C8A9	S	51.369
42	4142682D	S	26.669
42	414245EB	S	17.899""",
        """43	41430000	S	0.0
43	41430101	S	100.7843137254902
43	41437F7F	S	12799.607843137255
43	41438080	S	12900.392156862745
43	4143FFFF	S	25700.0
43	41432935	S	4136.862745098039
43	414310D5	S	1689.8039215686274
43	414375E6	S	11836.078431372549
43	41431BFF	S	2810.5882352941176
43	414338E7	S	5712.549019607844
43	41431B6B	S	2752.549019607843
43	41433FDD	S	6411.3725490196075
43	41437825	S	12061.56862745098
43	4143B3B8	S	18042.352941176472""",
        """44	41440000	S	0.0
44	41440101	S	0.007843017578125
44	41447F7F	S	0.996063232421875
44	41448080	S	1.00390625
44	4144FFFF	S	1.999969482421875
44	4144BF91	S	1.496612548828125
44	4144805F	S	1.002899169921875
44	4144F3C7	S	1.904510498046875
44	4144323E	S	0.39251708984375
44	4144F491	S	1.910675048828125
44	4144D513	S	1.664642333984375
44	414488D7	S	1.069061279296875
44	41444D21	S	0.602569580078125
44	4144D6CC	S	1.6781005859375""",
        """45	414500	S	0.0
45	414501	S	0.39215686274509803
45	41457F	S	49.80392156862745
45	414580	S	50.19607843137255
45	4145FF	S	100.0
45	41456A	S	41.568627450980394
45	4145D6	S	83.92156862745098
45	414587	S	52.94117647058823
45	414546	S	27.45098039215686
45	4145FE	S	99.6078431372549
45	4145FB	S	98.43137254901961
45	41454E	S	30.58823529411765
45	414529	S	16.07843137254902
45	4145AB	S	67.05882352941177""",
        """46	414600	S	-40.0
46	414601	S	-39.0
46	41467F	S	87.0
46	414680	S	88.0
46	4146FF	S	215.0
46	4146A7	S	127.0
46	41461F	S	-9.0
46	414693	S	107.0
46	414692	S	106.0
46	4146D8	S	176.0
46	4146A2	S	122.0
46	4146C8	S	160.0
46	4146FD	S	213.0
46	414691	S	105.0""",
        """47	414700	S	0.0
47	414701	S	0.39215686274509803
47	41477F	S	49.80392156862745
47	414780	S	50.19607843137255
47	4147FF	S	100.0
47	414717	S	9.019607843137255
47	4147A1	S	63.13725490196079
47	41475A	S	35.294117647058826
47	4147A6	S	65.09803921568627
47	414771	S	44.31372549019608
47	4147AA	S	66.66666666666667
47	4147CC	S	80.0
47	4147F3	S	95.29411764705883
47	41472A	S	16.470588235294116""",
        """48	414800	S	0.0
48	414801	S	0.39215686274509803
48	41487F	S	49.80392156862745
48	414880	S	50.19607843137255
48	4148FF	S	100.0
48	4148B2	S	69.80392156862744
48	41483A	S	22.745098039215687
48	414871	S	44.31372549019608
48	414865	S	39.6078431372549
48	414816	S	8.627450980392156
48	414889	S	53.72549019607843
48	4148CB	S	79.6078431372549
48	414844	S	26.666666666666668""",
        """49	414900	S	0.0
49	414901	S	0.39215686274509803
49	41497F	S	49.80392156862745
49	414980	S	50.19607843137255
49	4149FF	S	100.0
49	41490A	S	3.9215686274509802
49	41499B	S	60.78431372549019
49	41495C	S	36.07843137254902
49	4149E3	S	89.01960784313725
49	4149B1	S	69.41176470588235
49	4149EE	S	93.33333333333333
49	414919	S	9.803921568627452
49	41499C	S	61.1764705882353
49	4149EF	S	93.72549019607843""",
        """4A	414A00	S	0.0
4A	414A01	S	0.39215686274509803
4A	414A7F	S	49.80392156862745
4A	414A80	S	50.19607843137255
4A	414AFF	S	100.0
4A	414AC7	S	78.03921568627452
4A	414A92	S	57.254901960784316
4A	414A9D	S	61.568627450980394
4A	414AE9	S	91.37254901960785
4A	414AD8	S	84.70588235294117
4A	414AD3	S	82.74509803921569
4A	414A04	S	1.5686274509803921
4A	414AC1	S	75.68627450980392
4A	414A5B	S	35.68627450980392""",
        """4B	414B00	S	0.0
4B	414B01	S	0.39215686274509803
4B	414B7F	S	49.80392156862745
4B	414B80	S	50.19607843137255
4B	414BFF	S	100.0
4B	414B5A	S	35.294117647058826
4B	414B84	S	51.76470588235294
4B	414B8C	S	54.90196078431372
4B	414BD4	S	83.13725490196079
4B	414B72	S	44.705882352941174
4B	414BEA	S	91.76470588235294
4B	414B19	S	9.803921568627452
4B	414B2A	S	16.470588235294116
4B	414BFE	S	99.6078431372549""",
        """4C	414C00	S	0.0
4C	414C01	S	0.39215686274509803
4C	414C7F	S	49.80392156862745
4C	414C80	S	50.19607843137255
4C	414CFF	S	100.0
4C	414C9F	S	62.35294117647059
4C	414C1B	S	10.588235294117647
4C	414C0A	S	3.9215686274509802
4C	414C88	S	53.333333333333336
4C	414C41	S	25.49019607843137
4C	414C73	S	45.09803921568628
4C	414C1E	S	11.764705882352942
4C	414C40	S	25.098039215686274""",
        """4D	414D0000	I	0
4D	414D0101	I	257
4D	414D7F7F	I	32639
4D	414D8080	I	32896
4D	414DFFFF	I	65535
4D	414D23E2	I	9186
4D	414DE90F	I	59663
4D	414D5B4E	I	23374
4D	414D67B4	I	26548
4D	414D1DF0	I	7664
4D	414D9DFF	I	40447
4D	414DA60E	I	42510
4D	414DDD4C	I	56652
4D	414D204C	I	8268""",
        """4E	414E0000	I	0
4E	414E0101	I	257
4E	414E7F7F	I	32639
4E	414E8080	I	32896
4E	414EFFFF	I	65535
4E	414EEFC7	I	61383
4E	414E6A87	I	27271
4E	414E837D	I	33661
4E	414EB571	I	46449
4E	414E9F08	I	40712
4E	414EAC25	I	44069
4E	414E9AC1	I	39617
4E	414E2008	I	8200
4E	414E4C7E	I	19582""",
        """4F	414F00000000	F	equivalence_ratio=0|oxygen_sensor_voltage_volts=0|oxygen_sensor_current_milliamps=0|intake_manifold_pressure_kilopascals=0
4F	414F01010101	F	equivalence_ratio=1|oxygen_sensor_voltage_volts=1|oxygen_sensor_current_milliamps=1|intake_manifold_pressure_kilopascals=10
4F	414F7F7F7F7F	F	equivalence_ratio=127|oxygen_sensor_voltage_volts=127|oxygen_sensor_current_milliamps=127|intake_manifold_pressure_kilopascals=1270
4F	414F80808080	F	equivalence_ratio=128|oxygen_sensor_voltage_volts=128|oxygen_sensor_current_milliamps=128|intake_manifold_pressure_kilopascals=1280
4F	414FFFFFFFFF	F	equivalence_ratio=255|oxygen_sensor_voltage_volts=255|oxygen_sensor_current_milliamps=255|intake_manifold_pressure_kilopascals=2550
4F	414F9269A3BD	F	equivalence_ratio=146|oxygen_sensor_voltage_volts=105|oxygen_sensor_current_milliamps=163|intake_manifold_pressure_kilopascals=1890
4F	414F76C4A57D	F	equivalence_ratio=118|oxygen_sensor_voltage_volts=196|oxygen_sensor_current_milliamps=165|intake_manifold_pressure_kilopascals=1250
4F	414FD40A8292	F	equivalence_ratio=212|oxygen_sensor_voltage_volts=10|oxygen_sensor_current_milliamps=130|intake_manifold_pressure_kilopascals=1460
4F	414FF70EEF0D	F	equivalence_ratio=247|oxygen_sensor_voltage_volts=14|oxygen_sensor_current_milliamps=239|intake_manifold_pressure_kilopascals=130
4F	414FCAF74417	F	equivalence_ratio=202|oxygen_sensor_voltage_volts=247|oxygen_sensor_current_milliamps=68|intake_manifold_pressure_kilopascals=230
4F	414F04D72B0E	F	equivalence_ratio=4|oxygen_sensor_voltage_volts=215|oxygen_sensor_current_milliamps=43|intake_manifold_pressure_kilopascals=140
4F	414F5726D447	F	equivalence_ratio=87|oxygen_sensor_voltage_volts=38|oxygen_sensor_current_milliamps=212|intake_manifold_pressure_kilopascals=710
4F	414FD4623DCE	F	equivalence_ratio=212|oxygen_sensor_voltage_volts=98|oxygen_sensor_current_milliamps=61|intake_manifold_pressure_kilopascals=2060
4F	414FC117404D	F	equivalence_ratio=193|oxygen_sensor_voltage_volts=23|oxygen_sensor_current_milliamps=64|intake_manifold_pressure_kilopascals=770""",
        """50	415000	S	0.0
50	415001	S	10.0
50	41507F	S	1270.0
50	415080	S	1280.0
50	4150FF	S	2550.0
50	4150B3	S	1790.0
50	415034	S	520.0
50	41504D	S	770.0
50	41503C	S	600.0
50	415051	S	810.0
50	415040	S	640.0
50	41505C	S	920.0
50	415028	S	400.0
50	4150F8	S	2480.0""",
        """51	415100	F	pid=81|code=0|meaning=not available
51	415101	F	pid=81|code=1|meaning=gasoline
51	41517F	F	pid=81|code=127|meaning=reserved by ISO/SAE
51	415180	F	pid=81|code=128|meaning=reserved by ISO/SAE
51	4151FF	F	pid=81|code=255|meaning=reserved by ISO/SAE
51	4151CB	F	pid=81|code=203|meaning=reserved by ISO/SAE
51	415183	F	pid=81|code=131|meaning=reserved by ISO/SAE
51	41515B	F	pid=81|code=91|meaning=reserved by ISO/SAE
51	415197	F	pid=81|code=151|meaning=reserved by ISO/SAE
51	4151AF	F	pid=81|code=175|meaning=reserved by ISO/SAE
51	4151FC	F	pid=81|code=252|meaning=reserved by ISO/SAE
51	415139	F	pid=81|code=57|meaning=reserved by ISO/SAE
51	4151A8	F	pid=81|code=168|meaning=reserved by ISO/SAE
51	41513D	F	pid=81|code=61|meaning=reserved by ISO/SAE
51	415102	F	pid=81|code=2|meaning=methanol
51	415103	F	pid=81|code=3|meaning=ethanol
51	415104	F	pid=81|code=4|meaning=diesel
51	415105	F	pid=81|code=5|meaning=LPG
51	415106	F	pid=81|code=6|meaning=CNG
51	415107	F	pid=81|code=7|meaning=propane
51	415108	F	pid=81|code=8|meaning=electric
51	415109	F	pid=81|code=9|meaning=bifuel gasoline
51	41510A	F	pid=81|code=10|meaning=bifuel methanol
51	41510B	F	pid=81|code=11|meaning=bifuel ethanol
51	41510C	F	pid=81|code=12|meaning=bifuel LPG
51	41510D	F	pid=81|code=13|meaning=bifuel CNG
51	41510E	F	pid=81|code=14|meaning=bifuel propane
51	41510F	F	pid=81|code=15|meaning=bifuel electricity
51	415110	F	pid=81|code=16|meaning=bifuel electric and combustion engine
51	415111	F	pid=81|code=17|meaning=hybrid gasoline
51	415112	F	pid=81|code=18|meaning=hybrid ethanol
51	415113	F	pid=81|code=19|meaning=hybrid diesel
51	415114	F	pid=81|code=20|meaning=hybrid electric
51	415115	F	pid=81|code=21|meaning=hybrid electric and combustion engine
51	415116	F	pid=81|code=22|meaning=hybrid regenerative
51	415117	F	pid=81|code=23|meaning=bifuel diesel
51	415118	F	pid=81|code=24|meaning=reserved by ISO/SAE
51	415119	F	pid=81|code=25|meaning=reserved by ISO/SAE
51	41511A	F	pid=81|code=26|meaning=reserved by ISO/SAE
51	41511B	F	pid=81|code=27|meaning=reserved by ISO/SAE
51	41511C	F	pid=81|code=28|meaning=reserved by ISO/SAE
51	41511D	F	pid=81|code=29|meaning=reserved by ISO/SAE
51	41511E	F	pid=81|code=30|meaning=reserved by ISO/SAE
51	41511F	F	pid=81|code=31|meaning=reserved by ISO/SAE
51	415120	F	pid=81|code=32|meaning=reserved by ISO/SAE
51	415121	F	pid=81|code=33|meaning=reserved by ISO/SAE
51	415122	F	pid=81|code=34|meaning=reserved by ISO/SAE
51	415123	F	pid=81|code=35|meaning=reserved by ISO/SAE
51	415124	F	pid=81|code=36|meaning=reserved by ISO/SAE
51	415125	F	pid=81|code=37|meaning=reserved by ISO/SAE
51	415126	F	pid=81|code=38|meaning=reserved by ISO/SAE
51	415127	F	pid=81|code=39|meaning=reserved by ISO/SAE
51	415128	F	pid=81|code=40|meaning=reserved by ISO/SAE
51	415129	F	pid=81|code=41|meaning=reserved by ISO/SAE
51	41512A	F	pid=81|code=42|meaning=reserved by ISO/SAE
51	41512B	F	pid=81|code=43|meaning=reserved by ISO/SAE
51	41512C	F	pid=81|code=44|meaning=reserved by ISO/SAE
51	41512D	F	pid=81|code=45|meaning=reserved by ISO/SAE
51	41512E	F	pid=81|code=46|meaning=reserved by ISO/SAE
51	41512F	F	pid=81|code=47|meaning=reserved by ISO/SAE
51	415130	F	pid=81|code=48|meaning=reserved by ISO/SAE
51	415131	F	pid=81|code=49|meaning=reserved by ISO/SAE
51	415132	F	pid=81|code=50|meaning=reserved by ISO/SAE
51	415133	F	pid=81|code=51|meaning=reserved by ISO/SAE
51	415134	F	pid=81|code=52|meaning=reserved by ISO/SAE
51	415135	F	pid=81|code=53|meaning=reserved by ISO/SAE
51	415136	F	pid=81|code=54|meaning=reserved by ISO/SAE
51	415137	F	pid=81|code=55|meaning=reserved by ISO/SAE
51	415138	F	pid=81|code=56|meaning=reserved by ISO/SAE
51	41513A	F	pid=81|code=58|meaning=reserved by ISO/SAE
51	41513B	F	pid=81|code=59|meaning=reserved by ISO/SAE
51	41513C	F	pid=81|code=60|meaning=reserved by ISO/SAE
51	41513E	F	pid=81|code=62|meaning=reserved by ISO/SAE
51	41513F	F	pid=81|code=63|meaning=reserved by ISO/SAE
51	415140	F	pid=81|code=64|meaning=reserved by ISO/SAE
51	415141	F	pid=81|code=65|meaning=reserved by ISO/SAE
51	415142	F	pid=81|code=66|meaning=reserved by ISO/SAE
51	415143	F	pid=81|code=67|meaning=reserved by ISO/SAE
51	415144	F	pid=81|code=68|meaning=reserved by ISO/SAE
51	415145	F	pid=81|code=69|meaning=reserved by ISO/SAE
51	415146	F	pid=81|code=70|meaning=reserved by ISO/SAE
51	415147	F	pid=81|code=71|meaning=reserved by ISO/SAE
51	415148	F	pid=81|code=72|meaning=reserved by ISO/SAE
51	415149	F	pid=81|code=73|meaning=reserved by ISO/SAE
51	41514A	F	pid=81|code=74|meaning=reserved by ISO/SAE
51	41514B	F	pid=81|code=75|meaning=reserved by ISO/SAE
51	41514C	F	pid=81|code=76|meaning=reserved by ISO/SAE
51	41514D	F	pid=81|code=77|meaning=reserved by ISO/SAE
51	41514E	F	pid=81|code=78|meaning=reserved by ISO/SAE
51	41514F	F	pid=81|code=79|meaning=reserved by ISO/SAE
51	415150	F	pid=81|code=80|meaning=reserved by ISO/SAE
51	415151	F	pid=81|code=81|meaning=reserved by ISO/SAE
51	415152	F	pid=81|code=82|meaning=reserved by ISO/SAE
51	415153	F	pid=81|code=83|meaning=reserved by ISO/SAE
51	415154	F	pid=81|code=84|meaning=reserved by ISO/SAE
51	415155	F	pid=81|code=85|meaning=reserved by ISO/SAE
51	415156	F	pid=81|code=86|meaning=reserved by ISO/SAE
51	415157	F	pid=81|code=87|meaning=reserved by ISO/SAE
51	415158	F	pid=81|code=88|meaning=reserved by ISO/SAE
51	415159	F	pid=81|code=89|meaning=reserved by ISO/SAE
51	41515A	F	pid=81|code=90|meaning=reserved by ISO/SAE
51	41515C	F	pid=81|code=92|meaning=reserved by ISO/SAE
51	41515D	F	pid=81|code=93|meaning=reserved by ISO/SAE
51	41515E	F	pid=81|code=94|meaning=reserved by ISO/SAE
51	41515F	F	pid=81|code=95|meaning=reserved by ISO/SAE
51	415160	F	pid=81|code=96|meaning=reserved by ISO/SAE
51	415161	F	pid=81|code=97|meaning=reserved by ISO/SAE
51	415162	F	pid=81|code=98|meaning=reserved by ISO/SAE
51	415163	F	pid=81|code=99|meaning=reserved by ISO/SAE
51	415164	F	pid=81|code=100|meaning=reserved by ISO/SAE
51	415165	F	pid=81|code=101|meaning=reserved by ISO/SAE
51	415166	F	pid=81|code=102|meaning=reserved by ISO/SAE
51	415167	F	pid=81|code=103|meaning=reserved by ISO/SAE
51	415168	F	pid=81|code=104|meaning=reserved by ISO/SAE
51	415169	F	pid=81|code=105|meaning=reserved by ISO/SAE
51	41516A	F	pid=81|code=106|meaning=reserved by ISO/SAE
51	41516B	F	pid=81|code=107|meaning=reserved by ISO/SAE
51	41516C	F	pid=81|code=108|meaning=reserved by ISO/SAE
51	41516D	F	pid=81|code=109|meaning=reserved by ISO/SAE
51	41516E	F	pid=81|code=110|meaning=reserved by ISO/SAE
51	41516F	F	pid=81|code=111|meaning=reserved by ISO/SAE
51	415170	F	pid=81|code=112|meaning=reserved by ISO/SAE
51	415171	F	pid=81|code=113|meaning=reserved by ISO/SAE
51	415172	F	pid=81|code=114|meaning=reserved by ISO/SAE
51	415173	F	pid=81|code=115|meaning=reserved by ISO/SAE
51	415174	F	pid=81|code=116|meaning=reserved by ISO/SAE
51	415175	F	pid=81|code=117|meaning=reserved by ISO/SAE
51	415176	F	pid=81|code=118|meaning=reserved by ISO/SAE
51	415177	F	pid=81|code=119|meaning=reserved by ISO/SAE
51	415178	F	pid=81|code=120|meaning=reserved by ISO/SAE
51	415179	F	pid=81|code=121|meaning=reserved by ISO/SAE
51	41517A	F	pid=81|code=122|meaning=reserved by ISO/SAE
51	41517B	F	pid=81|code=123|meaning=reserved by ISO/SAE
51	41517C	F	pid=81|code=124|meaning=reserved by ISO/SAE
51	41517D	F	pid=81|code=125|meaning=reserved by ISO/SAE
51	41517E	F	pid=81|code=126|meaning=reserved by ISO/SAE
51	415181	F	pid=81|code=129|meaning=reserved by ISO/SAE
51	415182	F	pid=81|code=130|meaning=reserved by ISO/SAE
51	415184	F	pid=81|code=132|meaning=reserved by ISO/SAE
51	415185	F	pid=81|code=133|meaning=reserved by ISO/SAE
51	415186	F	pid=81|code=134|meaning=reserved by ISO/SAE
51	415187	F	pid=81|code=135|meaning=reserved by ISO/SAE
51	415188	F	pid=81|code=136|meaning=reserved by ISO/SAE
51	415189	F	pid=81|code=137|meaning=reserved by ISO/SAE
51	41518A	F	pid=81|code=138|meaning=reserved by ISO/SAE
51	41518B	F	pid=81|code=139|meaning=reserved by ISO/SAE
51	41518C	F	pid=81|code=140|meaning=reserved by ISO/SAE
51	41518D	F	pid=81|code=141|meaning=reserved by ISO/SAE
51	41518E	F	pid=81|code=142|meaning=reserved by ISO/SAE
51	41518F	F	pid=81|code=143|meaning=reserved by ISO/SAE
51	415190	F	pid=81|code=144|meaning=reserved by ISO/SAE
51	415191	F	pid=81|code=145|meaning=reserved by ISO/SAE
51	415192	F	pid=81|code=146|meaning=reserved by ISO/SAE
51	415193	F	pid=81|code=147|meaning=reserved by ISO/SAE
51	415194	F	pid=81|code=148|meaning=reserved by ISO/SAE
51	415195	F	pid=81|code=149|meaning=reserved by ISO/SAE
51	415196	F	pid=81|code=150|meaning=reserved by ISO/SAE
51	415198	F	pid=81|code=152|meaning=reserved by ISO/SAE
51	415199	F	pid=81|code=153|meaning=reserved by ISO/SAE
51	41519A	F	pid=81|code=154|meaning=reserved by ISO/SAE
51	41519B	F	pid=81|code=155|meaning=reserved by ISO/SAE
51	41519C	F	pid=81|code=156|meaning=reserved by ISO/SAE
51	41519D	F	pid=81|code=157|meaning=reserved by ISO/SAE
51	41519E	F	pid=81|code=158|meaning=reserved by ISO/SAE
51	41519F	F	pid=81|code=159|meaning=reserved by ISO/SAE
51	4151A0	F	pid=81|code=160|meaning=reserved by ISO/SAE
51	4151A1	F	pid=81|code=161|meaning=reserved by ISO/SAE
51	4151A2	F	pid=81|code=162|meaning=reserved by ISO/SAE
51	4151A3	F	pid=81|code=163|meaning=reserved by ISO/SAE
51	4151A4	F	pid=81|code=164|meaning=reserved by ISO/SAE
51	4151A5	F	pid=81|code=165|meaning=reserved by ISO/SAE
51	4151A6	F	pid=81|code=166|meaning=reserved by ISO/SAE
51	4151A7	F	pid=81|code=167|meaning=reserved by ISO/SAE
51	4151A9	F	pid=81|code=169|meaning=reserved by ISO/SAE
51	4151AA	F	pid=81|code=170|meaning=reserved by ISO/SAE
51	4151AB	F	pid=81|code=171|meaning=reserved by ISO/SAE
51	4151AC	F	pid=81|code=172|meaning=reserved by ISO/SAE
51	4151AD	F	pid=81|code=173|meaning=reserved by ISO/SAE
51	4151AE	F	pid=81|code=174|meaning=reserved by ISO/SAE
51	4151B0	F	pid=81|code=176|meaning=reserved by ISO/SAE
51	4151B1	F	pid=81|code=177|meaning=reserved by ISO/SAE
51	4151B2	F	pid=81|code=178|meaning=reserved by ISO/SAE
51	4151B3	F	pid=81|code=179|meaning=reserved by ISO/SAE
51	4151B4	F	pid=81|code=180|meaning=reserved by ISO/SAE
51	4151B5	F	pid=81|code=181|meaning=reserved by ISO/SAE
51	4151B6	F	pid=81|code=182|meaning=reserved by ISO/SAE
51	4151B7	F	pid=81|code=183|meaning=reserved by ISO/SAE
51	4151B8	F	pid=81|code=184|meaning=reserved by ISO/SAE
51	4151B9	F	pid=81|code=185|meaning=reserved by ISO/SAE
51	4151BA	F	pid=81|code=186|meaning=reserved by ISO/SAE
51	4151BB	F	pid=81|code=187|meaning=reserved by ISO/SAE
51	4151BC	F	pid=81|code=188|meaning=reserved by ISO/SAE
51	4151BD	F	pid=81|code=189|meaning=reserved by ISO/SAE
51	4151BE	F	pid=81|code=190|meaning=reserved by ISO/SAE
51	4151BF	F	pid=81|code=191|meaning=reserved by ISO/SAE
51	4151C0	F	pid=81|code=192|meaning=reserved by ISO/SAE
51	4151C1	F	pid=81|code=193|meaning=reserved by ISO/SAE
51	4151C2	F	pid=81|code=194|meaning=reserved by ISO/SAE
51	4151C3	F	pid=81|code=195|meaning=reserved by ISO/SAE
51	4151C4	F	pid=81|code=196|meaning=reserved by ISO/SAE
51	4151C5	F	pid=81|code=197|meaning=reserved by ISO/SAE
51	4151C6	F	pid=81|code=198|meaning=reserved by ISO/SAE
51	4151C7	F	pid=81|code=199|meaning=reserved by ISO/SAE
51	4151C8	F	pid=81|code=200|meaning=reserved by ISO/SAE
51	4151C9	F	pid=81|code=201|meaning=reserved by ISO/SAE
51	4151CA	F	pid=81|code=202|meaning=reserved by ISO/SAE
51	4151CC	F	pid=81|code=204|meaning=reserved by ISO/SAE
51	4151CD	F	pid=81|code=205|meaning=reserved by ISO/SAE
51	4151CE	F	pid=81|code=206|meaning=reserved by ISO/SAE
51	4151CF	F	pid=81|code=207|meaning=reserved by ISO/SAE
51	4151D0	F	pid=81|code=208|meaning=reserved by ISO/SAE
51	4151D1	F	pid=81|code=209|meaning=reserved by ISO/SAE
51	4151D2	F	pid=81|code=210|meaning=reserved by ISO/SAE
51	4151D3	F	pid=81|code=211|meaning=reserved by ISO/SAE
51	4151D4	F	pid=81|code=212|meaning=reserved by ISO/SAE
51	4151D5	F	pid=81|code=213|meaning=reserved by ISO/SAE
51	4151D6	F	pid=81|code=214|meaning=reserved by ISO/SAE
51	4151D7	F	pid=81|code=215|meaning=reserved by ISO/SAE
51	4151D8	F	pid=81|code=216|meaning=reserved by ISO/SAE
51	4151D9	F	pid=81|code=217|meaning=reserved by ISO/SAE
51	4151DA	F	pid=81|code=218|meaning=reserved by ISO/SAE
51	4151DB	F	pid=81|code=219|meaning=reserved by ISO/SAE
51	4151DC	F	pid=81|code=220|meaning=reserved by ISO/SAE
51	4151DD	F	pid=81|code=221|meaning=reserved by ISO/SAE
51	4151DE	F	pid=81|code=222|meaning=reserved by ISO/SAE
51	4151DF	F	pid=81|code=223|meaning=reserved by ISO/SAE
51	4151E0	F	pid=81|code=224|meaning=reserved by ISO/SAE
51	4151E1	F	pid=81|code=225|meaning=reserved by ISO/SAE
51	4151E2	F	pid=81|code=226|meaning=reserved by ISO/SAE
51	4151E3	F	pid=81|code=227|meaning=reserved by ISO/SAE
51	4151E4	F	pid=81|code=228|meaning=reserved by ISO/SAE
51	4151E5	F	pid=81|code=229|meaning=reserved by ISO/SAE
51	4151E6	F	pid=81|code=230|meaning=reserved by ISO/SAE
51	4151E7	F	pid=81|code=231|meaning=reserved by ISO/SAE
51	4151E8	F	pid=81|code=232|meaning=reserved by ISO/SAE
51	4151E9	F	pid=81|code=233|meaning=reserved by ISO/SAE
51	4151EA	F	pid=81|code=234|meaning=reserved by ISO/SAE
51	4151EB	F	pid=81|code=235|meaning=reserved by ISO/SAE
51	4151EC	F	pid=81|code=236|meaning=reserved by ISO/SAE
51	4151ED	F	pid=81|code=237|meaning=reserved by ISO/SAE
51	4151EE	F	pid=81|code=238|meaning=reserved by ISO/SAE
51	4151EF	F	pid=81|code=239|meaning=reserved by ISO/SAE
51	4151F0	F	pid=81|code=240|meaning=reserved by ISO/SAE
51	4151F1	F	pid=81|code=241|meaning=reserved by ISO/SAE
51	4151F2	F	pid=81|code=242|meaning=reserved by ISO/SAE
51	4151F3	F	pid=81|code=243|meaning=reserved by ISO/SAE
51	4151F4	F	pid=81|code=244|meaning=reserved by ISO/SAE
51	4151F5	F	pid=81|code=245|meaning=reserved by ISO/SAE
51	4151F6	F	pid=81|code=246|meaning=reserved by ISO/SAE
51	4151F7	F	pid=81|code=247|meaning=reserved by ISO/SAE
51	4151F8	F	pid=81|code=248|meaning=reserved by ISO/SAE
51	4151F9	F	pid=81|code=249|meaning=reserved by ISO/SAE
51	4151FA	F	pid=81|code=250|meaning=reserved by ISO/SAE
51	4151FB	F	pid=81|code=251|meaning=reserved by ISO/SAE
51	4151FD	F	pid=81|code=253|meaning=reserved by ISO/SAE
51	4151FE	F	pid=81|code=254|meaning=reserved by ISO/SAE""",
        """52	415200	S	0.0
52	415201	S	0.39215686274509803
52	41527F	S	49.80392156862745
52	415280	S	50.19607843137255
52	4152FF	S	100.0
52	415202	S	0.7843137254901961
52	4152EE	S	93.33333333333333
52	4152B8	S	72.15686274509804
52	4152DD	S	86.66666666666667
52	41528E	S	55.68627450980392
52	4152CF	S	81.17647058823529
52	415214	S	7.8431372549019605
52	4152C0	S	75.29411764705883
52	41520E	S	5.490196078431373""",
        """53	41530000	S	0.0
53	41530101	S	1.285
53	41537F7F	S	163.195
53	41538080	S	164.48
53	4153FFFF	S	327.675
53	4153EEFE	S	305.91
53	4153CF9E	S	265.75
53	41532D99	S	58.365
53	415321DB	S	43.335
53	41531F53	S	40.095
53	4153BF8B	S	245.175
53	4153782F	S	153.835
53	4153C4AC	S	251.74
53	41533CF8	S	78.04""",
        """55	41550000	F	bank_1_percent=-100.0|bank_3_percent=-100.0
55	41550101	F	bank_1_percent=-99.21875|bank_3_percent=-99.21875
55	41557F7F	F	bank_1_percent=-0.78125|bank_3_percent=-0.78125
55	41558080	F	bank_1_percent=0.0|bank_3_percent=0.0
55	4155FFFF	F	bank_1_percent=99.21875|bank_3_percent=99.21875
55	41558BE8	F	bank_1_percent=8.59375|bank_3_percent=81.25
55	41552A03	F	bank_1_percent=-67.1875|bank_3_percent=-97.65625
55	4155EE4A	F	bank_1_percent=85.9375|bank_3_percent=-42.1875
55	41552332	F	bank_1_percent=-72.65625|bank_3_percent=-60.9375
55	4155C0EC	F	bank_1_percent=50.0|bank_3_percent=84.375
55	4155FC9D	F	bank_1_percent=96.875|bank_3_percent=22.65625
55	415598C8	F	bank_1_percent=18.75|bank_3_percent=56.25
55	41550F5C	F	bank_1_percent=-88.28125|bank_3_percent=-28.125
55	41559144	F	bank_1_percent=13.28125|bank_3_percent=-46.875""",
        """56	41560000	F	bank_1_percent=-100.0|bank_3_percent=-100.0
56	41560101	F	bank_1_percent=-99.21875|bank_3_percent=-99.21875
56	41567F7F	F	bank_1_percent=-0.78125|bank_3_percent=-0.78125
56	41568080	F	bank_1_percent=0.0|bank_3_percent=0.0
56	4156FFFF	F	bank_1_percent=99.21875|bank_3_percent=99.21875
56	4156BB0B	F	bank_1_percent=46.09375|bank_3_percent=-91.40625
56	4156107E	F	bank_1_percent=-87.5|bank_3_percent=-1.5625
56	41565917	F	bank_1_percent=-30.46875|bank_3_percent=-82.03125
56	415660D4	F	bank_1_percent=-25.0|bank_3_percent=65.625
56	41561CA8	F	bank_1_percent=-78.125|bank_3_percent=31.25
56	415622C4	F	bank_1_percent=-73.4375|bank_3_percent=53.125
56	41563B63	F	bank_1_percent=-53.90625|bank_3_percent=-22.65625
56	415618B6	F	bank_1_percent=-81.25|bank_3_percent=42.1875
56	41564A6A	F	bank_1_percent=-42.1875|bank_3_percent=-17.1875""",
        """59	41590000	S	0.0
59	41590101	S	2570.0
59	41597F7F	S	326390.0
59	41598080	S	328960.0
59	4159FFFF	S	655350.0
59	4159E303	S	581150.0
59	41591201	S	46090.0
59	4159DCA7	S	564870.0
59	4159B089	S	451930.0
59	4159F878	S	636080.0
59	41590BDB	S	30350.0
59	41594D03	S	197150.0
59	4159E51C	S	586520.0
59	4159F2DA	S	621700.0""",
        """5A	415A00	S	0.0
5A	415A01	S	0.39215686274509803
5A	415A7F	S	49.80392156862745
5A	415A80	S	50.19607843137255
5A	415AFF	S	100.0
5A	415AD6	S	83.92156862745098
5A	415ADD	S	86.66666666666667
5A	415A7A	S	47.84313725490196
5A	415ABF	S	74.90196078431373
5A	415AA1	S	63.13725490196079
5A	415A73	S	45.09803921568628
5A	415AC1	S	75.68627450980392
5A	415A50	S	31.372549019607842
5A	415A3A	S	22.745098039215687""",
        """5B	415B00	S	0.0
5B	415B01	S	0.39215686274509803
5B	415B7F	S	49.80392156862745
5B	415B80	S	50.19607843137255
5B	415BFF	S	100.0
5B	415BD2	S	82.3529411764706
5B	415BCE	S	80.7843137254902
5B	415B05	S	1.9607843137254901
5B	415B56	S	33.72549019607843
5B	415BE5	S	89.80392156862744
5B	415B09	S	3.5294117647058822
5B	415BD1	S	81.96078431372548
5B	415B88	S	53.333333333333336
5B	415B1E	S	11.764705882352942""",
        """5C	415C00	S	-40.0
5C	415C01	S	-39.0
5C	415C7F	S	87.0
5C	415C80	S	88.0
5C	415CFF	S	215.0
5C	415CD3	S	171.0
5C	415C96	S	110.0
5C	415C31	S	9.0
5C	415CBA	S	146.0
5C	415C03	S	-37.0
5C	415C34	S	12.0
5C	415CF2	S	202.0
5C	415C79	S	81.0""",
        """5D	415D0000	S	-210.0
5D	415D0101	S	-207.9921875
5D	415D7F7F	S	44.9921875
5D	415D8080	S	47.0
5D	415DFFFF	S	301.9921875
5D	415D7D7F	S	40.9921875
5D	415DAE4A	S	138.578125
5D	415D46A3	S	-68.7265625
5D	415DDB55	S	228.6640625
5D	415DD651	S	218.6328125
5D	415D6871	S	-1.1171875
5D	415D0908	S	-191.9375
5D	415D76B2	S	27.390625
5D	415D8ECF	S	75.6171875""",
        """5E	415E0000	S	0.0
5E	415E0101	S	12.850000000000001
5E	415E7F7F	S	1631.95
5E	415E8080	S	1644.8000000000002
5E	415EFFFF	S	3276.75
5E	415E103A	S	207.70000000000002
5E	415E6C1C	S	1383.8000000000002
5E	415EE6F7	S	2956.3500000000004
5E	415EBCFB	S	2418.9500000000003
5E	415E7654	S	1514.6000000000001
5E	415E9B7C	S	1990.2
5E	415E0B39	S	143.65
5E	415EDB9E	S	2811.1000000000004
5E	415E571B	S	1114.95""",
        """61	416100	S	-125.0
61	416101	S	-124.0
61	41617F	S	2.0
61	416180	S	3.0
61	4161FF	S	130.0
61	4161D1	S	84.0
61	4161A0	S	35.0
61	4161BA	S	61.0
61	4161C9	S	76.0
61	41614B	S	-50.0
61	416130	S	-77.0
61	41612E	S	-79.0
61	4161EA	S	109.0
61	4161B9	S	60.0""",
        """62	416200	S	-125.0
62	416201	S	-124.0
62	41627F	S	2.0
62	416280	S	3.0
62	4162FF	S	130.0
62	4162CB	S	78.0
62	4162A7	S	42.0
62	4162C7	S	74.0
62	4162EC	S	111.0
62	41621F	S	-94.0
62	416208	S	-117.0
62	4162A8	S	43.0
62	4162F0	S	115.0
62	41629E	S	33.0""",
        """63	41630000	I	0
63	41630101	I	257
63	41637F7F	I	32639
63	41638080	I	32896
63	4163FFFF	I	65535
63	4163278B	I	10123
63	4163AFF0	I	45040
63	4163800C	I	32780
63	41633D66	I	15718
63	4163D191	I	53649
63	41635D02	I	23810
63	4163D152	I	53586
63	41634604	I	17924
63	41632C35	I	11317""",
        """66	41660000000000	F	sensor_a_grams_per_second=unavailable|sensor_b_grams_per_second=unavailable
66	41660101010101	F	sensor_a_grams_per_second=8.03125|sensor_b_grams_per_second=unavailable
66	41667F7F7F7F7F	F	sensor_a_grams_per_second=1019.96875|sensor_b_grams_per_second=1019.96875
66	41668080808080	F	sensor_a_grams_per_second=unavailable|sensor_b_grams_per_second=unavailable
66	4166FFFFFFFFFF	F	sensor_a_grams_per_second=2047.96875|sensor_b_grams_per_second=2047.96875
66	416668E28077E8	F	sensor_a_grams_per_second=unavailable|sensor_b_grams_per_second=unavailable
66	4166B10536F512	F	sensor_a_grams_per_second=41.6875|sensor_b_grams_per_second=unavailable
66	4166B4D08052E4	F	sensor_a_grams_per_second=unavailable|sensor_b_grams_per_second=unavailable
66	416660A9119210	F	sensor_a_grams_per_second=unavailable|sensor_b_grams_per_second=unavailable
66	41663FBBBF6AC1	F	sensor_a_grams_per_second=1501.96875|sensor_b_grams_per_second=854.03125
66	416683B0ED2B95	F	sensor_a_grams_per_second=1415.40625|sensor_b_grams_per_second=348.65625
66	4166526F90BC33	F	sensor_a_grams_per_second=unavailable|sensor_b_grams_per_second=1505.59375
66	41663EEFD66402	F	sensor_a_grams_per_second=unavailable|sensor_b_grams_per_second=800.0625
66	416687260A4C27	F	sensor_a_grams_per_second=304.3125|sensor_b_grams_per_second=609.21875
66	416600FFFFFFFF	F	sensor_a_grams_per_second=unavailable|sensor_b_grams_per_second=unavailable
66	416601FFFFFFFF	F	sensor_a_grams_per_second=2047.96875|sensor_b_grams_per_second=unavailable
66	416602FFFFFFFF	F	sensor_a_grams_per_second=unavailable|sensor_b_grams_per_second=2047.96875
66	416603FFFFFFFF	F	sensor_a_grams_per_second=2047.96875|sensor_b_grams_per_second=2047.96875""",
        """67	4167000000	F	sensor_1_celsius=unavailable|sensor_2_celsius=unavailable
67	4167010101	F	sensor_1_celsius=-39.0|sensor_2_celsius=unavailable
67	41677F7F7F	F	sensor_1_celsius=87.0|sensor_2_celsius=87.0
67	4167808080	F	sensor_1_celsius=unavailable|sensor_2_celsius=unavailable
67	4167FFFFFF	F	sensor_1_celsius=215.0|sensor_2_celsius=215.0
67	4167DCC39F	F	sensor_1_celsius=unavailable|sensor_2_celsius=unavailable
67	416713D993	F	sensor_1_celsius=177.0|sensor_2_celsius=107.0
67	41678DCECA	F	sensor_1_celsius=166.0|sensor_2_celsius=unavailable
67	416737E0DE	F	sensor_1_celsius=184.0|sensor_2_celsius=182.0
67	4167714EF8	F	sensor_1_celsius=38.0|sensor_2_celsius=unavailable
67	4167D1EF77	F	sensor_1_celsius=199.0|sensor_2_celsius=unavailable
67	416771AC22	F	sensor_1_celsius=132.0|sensor_2_celsius=unavailable
67	41679E887D	F	sensor_1_celsius=unavailable|sensor_2_celsius=85.0
67	4167CC27D1	F	sensor_1_celsius=unavailable|sensor_2_celsius=unavailable
67	416700FFFF	F	sensor_1_celsius=unavailable|sensor_2_celsius=unavailable
67	416701FFFF	F	sensor_1_celsius=215.0|sensor_2_celsius=unavailable
67	416702FFFF	F	sensor_1_celsius=unavailable|sensor_2_celsius=215.0
67	416703FFFF	F	sensor_1_celsius=215.0|sensor_2_celsius=215.0""",
        """68	4168000000	F	sensor_1_celsius=unavailable|sensor_2_celsius=unavailable
68	4168010101	F	sensor_1_celsius=-39.0|sensor_2_celsius=unavailable
68	41687F7F7F	F	sensor_1_celsius=87.0|sensor_2_celsius=87.0
68	4168808080	F	sensor_1_celsius=unavailable|sensor_2_celsius=unavailable
68	4168FFFFFF	F	sensor_1_celsius=215.0|sensor_2_celsius=215.0
68	4168CA5063	F	sensor_1_celsius=unavailable|sensor_2_celsius=59.0
68	4168DCEEBA	F	sensor_1_celsius=unavailable|sensor_2_celsius=unavailable
68	41687C00A1	F	sensor_1_celsius=unavailable|sensor_2_celsius=unavailable
68	41680A0D1E	F	sensor_1_celsius=unavailable|sensor_2_celsius=-10.0
68	41685AB89B	F	sensor_1_celsius=unavailable|sensor_2_celsius=115.0
68	4168218A86	F	sensor_1_celsius=98.0|sensor_2_celsius=unavailable
68	4168E036C5	F	sensor_1_celsius=unavailable|sensor_2_celsius=unavailable
68	416879511D	F	sensor_1_celsius=41.0|sensor_2_celsius=unavailable
68	4168C812DA	F	sensor_1_celsius=unavailable|sensor_2_celsius=unavailable
68	416800FFFF	F	sensor_1_celsius=unavailable|sensor_2_celsius=unavailable
68	416801FFFF	F	sensor_1_celsius=215.0|sensor_2_celsius=unavailable
68	416802FFFF	F	sensor_1_celsius=unavailable|sensor_2_celsius=215.0
68	416803FFFF	F	sensor_1_celsius=215.0|sensor_2_celsius=215.0""",
        """8D	418D00	S	0.0
8D	418D01	S	0.39215686274509803
8D	418D7F	S	49.80392156862745
8D	418D80	S	50.19607843137255
8D	418DFF	S	100.0
8D	418D94	S	58.03921568627451
8D	418D09	S	3.5294117647058822
8D	418D32	S	19.607843137254903
8D	418DEB	S	92.15686274509804
8D	418D78	S	47.05882352941177
8D	418DF5	S	96.07843137254902
8D	418D37	S	21.568627450980394
8D	418D3C	S	23.529411764705884""",
        """8E	418E00	S	-125.0
8E	418E01	S	-124.0
8E	418E7F	S	2.0
8E	418E80	S	3.0
8E	418EFF	S	130.0
8E	418EC6	S	73.0
8E	418ECA	S	77.0
8E	418E7E	S	1.0
8E	418EE7	S	106.0
8E	418E36	S	-71.0
8E	418E08	S	-117.0
8E	418ED9	S	92.0
8E	418E76	S	-7.0
8E	418EB6	S	57.0""",
        """A4	41A40000	S	0.0
A4	41A40101	S	0.257
A4	41A47F7F	S	32.639
A4	41A48080	S	32.896
A4	41A4FFFF	S	65.535
A4	41A4DCB8	S	56.504
A4	41A4A7B1	S	42.929
A4	41A4EA1E	S	59.934
A4	41A46573	S	25.971
A4	41A45EC9	S	24.265
A4	41A48499	S	33.945
A4	41A4E51F	S	58.655
A4	41A4D026	S	53.286
A4	41A4142F	S	5.167""",
        """A5	41A500	S	0.0
A5	41A501	S	0.5
A5	41A57F	S	63.5
A5	41A580	S	64.0
A5	41A5FF	S	127.5
A5	41A5DA	S	109.0
A5	41A537	S	27.5
A5	41A5B8	S	92.0
A5	41A52E	S	23.0
A5	41A58D	S	70.5
A5	41A5F6	S	123.0
A5	41A50E	S	7.0""",
        """A6	41A600000000	S	0.0
A6	41A601010101	S	1684300.9
A6	41A67F7F7F7F	S	213906214.3
A6	41A680808080	S	215590515.2
A6	41A6FFFFFFFF	S	429496729.5
A6	41A64EF0AEA0	S	132439619.2
A6	41A6EB5E0D69	S	394880957.7
A6	41A6E1B82CC7	S	378694368.7
A6	41A6E8612966	S	389868170.2
A6	41A627D89B56	S	66850696.6
A6	41A6E92103D4	S	391125499.6
A6	41A671071D56	S	189629167.0
A6	41A6422D7862	S	111027619.4
A6	41A62C9EBB2D	S	74860010.9""",
    )
}
