package com.aguirre.pulsealert.core

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.aguirre.pulsealert.data.local.MessageDao
import com.aguirre.pulsealert.data.local.MessageEntity

/**
 * Base de datos Room de la app. Singleton — existe una sola instancia
 * durante toda la vida de la aplicación.
 *
 * @Database lista todas las entidades (tablas) y define la versión.
 *
 * IMPORTANTE — versiones:
 * Cada vez que modifiques MessageEntity (agregar/quitar columnas),
 * debes incrementar `version` y proveer una Migration, o en desarrollo
 * puedes usar `fallbackToDestructiveMigration()` que borra y recrea
 * la DB (se pierden los datos, solo aceptable en desarrollo).
 */
@Database(
    entities = [MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    companion object {

        // @Volatile garantiza que todos los hilos vean el mismo valor.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Obtiene la instancia única de la base de datos.
         * Si no existe, la crea. Thread-safe gracias a synchronized.
         *
         * Uso desde cualquier parte de la app:
         *   val db = AppDatabase.getInstance(context)
         *   val dao = db.messageDao()
         *
         * Más adelante, cuando implementes inyección de dependencias
         * con Hilt, este método será reemplazado por un @Provides en
         * un módulo — pero por ahora funciona perfectamente.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pusealert_database"
                )
                    // Solo para desarrollo. Antes de producción reemplazar
                    // por migraciones reales con addMigrations(...).
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}